package com.beautica.appconfig.controller;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.Role;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 437 — {@code GET /api/v1/app/config} end to end: public, exact contract shape, env-backed values,
 * {@code Cache-Control: public, max-age=300}, a valid JWT changes nothing, a malformed {@code X-App-Version}
 * never causes a 4xx, and the OpenAPI spec marks every field required (mobile regen → non-null fields).
 */
@DisplayName("AppConfigControllerIT — GET /api/v1/app/config (Phase 437)")
class AppConfigControllerIT extends AbstractIntegrationTest {

    private static final String PATH = "/api/v1/app/config";
    private static final String DEFAULT_STORE_URL = "https://play.google.com/store/apps/details?id=com.beautica.app";
    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder(4);

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private ResponseEntity<String> get(HttpHeaders headers) {
        return restTemplate.exchange(PATH, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private JsonNode body(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody());
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    @Test
    @DisplayName("anonymous → 200 with exactly the contract shape and the default values")
    void should_returnDefaults_when_anonymousAndNoEnv() throws Exception {
        ResponseEntity<String> response = get(new HttpHeaders());

        JsonNode body = body(response);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body.path("success").asBoolean()).isTrue();
        assertThat(fieldNames(body.path("data"))).containsExactly("android");
        JsonNode android = body.path("data").path("android");
        assertThat(fieldNames(android)).containsExactlyInAnyOrder("minVersionCode", "latestVersionCode", "storeUrl");
        assertThat(android.path("minVersionCode").isInt()).isTrue();
        assertThat(android.path("minVersionCode").asInt()).isZero();
        assertThat(android.path("latestVersionCode").isInt()).isTrue();
        assertThat(android.path("latestVersionCode").asInt()).isZero();
        assertThat(android.path("storeUrl").asText()).isEqualTo(DEFAULT_STORE_URL);
    }

    @Test
    @DisplayName("success envelope — exactly success/data/message, message null, no errors or requestId key")
    void should_returnStandardSuccessEnvelope_when_served() throws Exception {
        ResponseEntity<String> response = get(new HttpHeaders());

        JsonNode body = body(response);
        assertThat(fieldNames(body)).containsExactlyInAnyOrder("success", "data", "message");
        assertThat(body.path("message").isNull()).as("message is JSON null").isTrue();
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().toString()).startsWith("application/json");
    }

    @Test
    @DisplayName("response carries Cache-Control: public, max-age=300 (no Spring Security no-cache headers)")
    void should_setCacheControl_when_served() {
        ResponseEntity<String> response = get(new HttpHeaders());

        assertThat(response.getHeaders().get(HttpHeaders.CACHE_CONTROL)).containsExactly("public, max-age=300");
        assertThat(response.getHeaders().getFirst(HttpHeaders.PRAGMA)).isNull();
    }

    @Test
    @DisplayName("a valid JWT does not change the response")
    void should_returnSameBody_when_validJwtPresent() throws Exception {
        UUID userId = insertClient();
        String token = jwtTokenProvider.generateAccessToken(userId, emailOf(userId), Role.CLIENT);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);

        ResponseEntity<String> anonymous = get(new HttpHeaders());
        ResponseEntity<String> authenticated = get(headers);

        assertThat(authenticated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(authenticated)).isEqualTo(body(anonymous));
        assertThat(authenticated.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("public, max-age=300");
    }

    @Test
    @DisplayName("malformed / oversized X-App-Version → still 200 through the full chain, value never reflected")
    void should_return200AndDropValue_when_appVersionMalformed() throws Exception {
        // MDC / Sentry-scope drop is pinned in RequestIdFilterAppVersionTest (not observable over HTTP: the log
        // pattern has no %X{appVersion}); this IT pins the full-chain half — no filter, Security rule or handler
        // rejects it, and the attacker-controlled value is echoed in no header and not in the body.
        for (String bad : List.of("not-a-version", "1.2.3", "1.2.3+" + "9".repeat(40), "<script>")) {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-App-Version", bad);

            ResponseEntity<String> response = get(headers);

            assertThat(response.getStatusCode()).as("X-App-Version=%s", bad).isEqualTo(HttpStatus.OK);
            assertThat(response.getHeaders().getFirst("X-Request-Id")).as("RequestIdFilter ran").isNotBlank();
            assertThat(response.getHeaders().toString()).as("no header echoes %s", bad).doesNotContain(bad);
            assertThat(response.getBody()).as("body does not echo %s", bad).doesNotContain(bad);
            assertThat(body(response)).as("body identical to a header-less call").isEqualTo(body(get(new HttpHeaders())));
        }
    }

    @Test
    @DisplayName("valid X-App-Version → 200")
    void should_return200_when_appVersionValid() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-App-Version", "1.4.0+27");

        assertThat(get(headers).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private UUID insertClient() {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                        + "VALUES (?, ?, ?, 'CLIENT', true, true)",
                userId, emailOf(userId), PASSWORD_ENCODER.encode("test-password"));
        return userId;
    }

    private static String emailOf(UUID userId) {
        return "appconfig-" + userId + "@beautica.test";
    }

    /** Env overrides — same property set as {@link AppConfigRateLimitIT}, so the two share one cached context. */
    @Nested
    @TestPropertySource(properties = {
            AppConfigRateLimitIT.CAPACITY_PROPERTY,
            AppConfigRateLimitIT.PRINCIPAL_CAPACITY_PROPERTY,
            AppConfigRateLimitIT.MIN_PROPERTY,
            AppConfigRateLimitIT.LATEST_PROPERTY,
            AppConfigRateLimitIT.STORE_URL_PROPERTY})
    @DisplayName("with app.client-version overrides")
    class WithEnvOverrides {

        // Own fields: the enclosing instance is injected from the OUTER (default) context, so its restTemplate
        // would hit the server without these overrides.
        @Autowired
        private TestRestTemplate overrideRestTemplate;

        @Autowired
        private ObjectMapper overrideObjectMapper;

        @Test
        @DisplayName("overridden min / latest / storeUrl are reflected in the response")
        void should_reflectOverrides_when_propertiesSet() throws Exception {
            HttpHeaders headers = new HttpHeaders();
            // Own IP: this context's catalogue bucket is lowered to 3 for AppConfigRateLimitIT.
            headers.set("X-Forwarded-For", AppConfigRateLimitIT.nextIp());

            ResponseEntity<String> response =
                    overrideRestTemplate.exchange(PATH, HttpMethod.GET, new HttpEntity<>(headers), String.class);

            JsonNode android = overrideObjectMapper.readTree(response.getBody()).path("data").path("android");
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(android.path("minVersionCode").asInt()).isEqualTo(41);
            assertThat(android.path("latestVersionCode").asInt()).isEqualTo(57);
            assertThat(android.path("storeUrl").asText())
                    .isEqualTo("https://play.google.com/store/apps/details?id=com.beautica.app.test");
        }
    }

    /** Same property set as the sibling api-docs contract ITs, so the cached context is reused. */
    @Nested
    @TestPropertySource(properties = {
            "springdoc.api-docs.enabled=true",
            "springdoc.api-docs.path=/api-docs"
    })
    @DisplayName("OpenAPI contract")
    class OpenApiContract {

        // Own fields: the enclosing instance is injected from the OUTER context, where api-docs is off.
        @Autowired
        private TestRestTemplate docsRestTemplate;

        @Autowired
        private ObjectMapper docsObjectMapper;

        @Test
        @DisplayName("/api-docs exposes GET /api/v1/app/config with every contract field required")
        void should_markAllFieldsRequired_when_specRendered() throws Exception {
            JsonNode spec = docsObjectMapper.readTree(
                    docsRestTemplate.getForEntity("/api-docs", String.class).getBody());

            JsonNode get = spec.path("paths").path(PATH).path("get");
            assertThat(get.isMissingNode()).as("endpoint in spec").isFalse();
            assertThat(get.path("security").isMissingNode() || get.path("security").isEmpty()).isTrue();
            JsonNode schemas = spec.path("components").path("schemas");
            assertThat(requiredOf(schemas.path("AppConfigResponse"))).containsExactly("android");
            assertThat(requiredOf(schemas.path("AndroidAppConfig")))
                    .containsExactlyInAnyOrder("minVersionCode", "latestVersionCode", "storeUrl");
            assertThat(schemas.path("AndroidAppConfig").path("properties").path("minVersionCode").path("type").asText())
                    .isEqualTo("integer");
        }

        private List<String> requiredOf(JsonNode schema) {
            List<String> required = new ArrayList<>();
            schema.path("required").forEach(node -> required.add(node.asText()));
            return required;
        }
    }
}
