package com.beautica.config;

import com.beautica.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B501 (SVC-54) — the catalog-scoped {@code X-Internal-Key} through the REAL filter chain (Tomcat, Spring Security,
 * {@link InternalApiKeyFilter}, MVC) on a real Postgres context.
 *
 * <p>No catalog controller exists yet (B502+), so a test-only probe is mapped under the catalog prefix. It is a nested
 * class, so {@code TestTypeExcludeFilter} keeps it out of component scanning: it exists ONLY in this context (via
 * {@link Import}) and never reaches any other context or the OpenAPI spec (unlike a top-level test controller).
 */
@DisplayName("InternalApiKeyFilter (IT) — catalog key opens only /api/v1/internal/catalog/**")
@TestPropertySource(properties = "app.internal-catalog-key=" + InternalCatalogKeyFilterIT.CATALOG_KEY)
@Import(InternalCatalogKeyFilterIT.CatalogProbeController.class)
class InternalCatalogKeyFilterIT extends AbstractIntegrationTest {

    /** 42 chars, distinct from the test master key; low entropy on purpose (secret scanner). */
    static final String CATALOG_KEY = "test-internal-catalog-key-bbbbbbbbbbbbbbbb";
    private static final String MASTER_KEY = "test-internal-api-key";
    private static final String PROBE_URL = "/api/v1/internal/catalog/qa-probe";
    private static final String MODERATION_URL = "/api/v1/internal/moderation/reports";
    private static final String GUEST_PII_URL = "/api/v1/internal/guest-pii/erase";

    @RestController
    static class CatalogProbeController {
        @GetMapping(PROBE_URL)
        Map<String, String> probe() {
            return Map.of("probe", "catalog-reached");
        }

        /** Shares the catalog word but NOT the {@code /catalog/} segment: must stay master-key only. */
        @GetMapping("/api/v1/internal/catalogue-lookalike")
        Map<String, String> lookalike() {
            return Map.of("probe", "lookalike-reached");
        }
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private ResponseEntity<String> call(HttpMethod method, String url, String key, String body) {
        HttpHeaders headers = new HttpHeaders();
        if (key != null) {
            headers.set("X-Internal-Key", key);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(url, method, new HttpEntity<>(body, headers), String.class);
    }

    private String probeValue(ResponseEntity<String> response) throws Exception {
        JsonNode node = objectMapper.readTree(response.getBody());
        return node.path("probe").asText();
    }

    private void assertUnauthorizedBody(ResponseEntity<String> response) throws Exception {
        JsonNode node = objectMapper.readTree(response.getBody());
        assertThat(node.path("success").asBoolean(true)).as("body: %s", response.getBody()).isFalse();
        assertThat(node.path("message").asText()).isEqualTo("Unauthorized");
    }

    @Test
    @DisplayName("catalog key → 200 on a /internal/catalog/** handler (passes Security + filter, reaches MVC)")
    void should_reachCatalogHandler_when_catalogKeySent() throws Exception {
        ResponseEntity<String> response = call(HttpMethod.GET, PROBE_URL, CATALOG_KEY, null);

        assertThat(response.getStatusCode()).as("body: %s", response.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(probeValue(response)).isEqualTo("catalog-reached");
    }

    @Test
    @DisplayName("catalog key → 401 on GET /internal/moderation/reports, generic body")
    void should_return401_when_catalogKeyUsedOnModerationQueue() throws Exception {
        ResponseEntity<String> response = call(HttpMethod.GET, MODERATION_URL, CATALOG_KEY, null);

        assertThat(response.getStatusCode()).as("body: %s", response.getBody()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertUnauthorizedBody(response);
    }

    @Test
    @DisplayName("catalog key → 401 on POST /internal/guest-pii/erase, generic body")
    void should_return401_when_catalogKeyUsedOnGuestPiiErase() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("phone", "+380501234567"));

        ResponseEntity<String> response = call(HttpMethod.POST, GUEST_PII_URL, CATALOG_KEY, body);

        assertThat(response.getStatusCode()).as("body: %s", response.getBody()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertUnauthorizedBody(response);
    }

    @Test
    @DisplayName("catalog key → 401 on /internal/catalogue-lookalike (prefix needs the /catalog/ segment)")
    void should_return401_when_catalogKeyUsedOnLookalikePrefix() throws Exception {
        ResponseEntity<String> response = call(HttpMethod.GET, "/api/v1/internal/catalogue-lookalike", CATALOG_KEY, null);

        assertThat(response.getStatusCode()).as("body: %s", response.getBody()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertUnauthorizedBody(response);
    }

    @Test
    @DisplayName("master key still opens a non-catalog internal path (GET /internal/moderation/reports → 200)")
    void should_openModerationQueue_when_masterKeySent() {
        ResponseEntity<String> response = call(HttpMethod.GET, MODERATION_URL, MASTER_KEY, null);

        assertThat(response.getStatusCode()).as("body: %s", response.getBody()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("master key also opens the catalog prefix (SVC-54: master OR catalog key)")
    void should_reachCatalogHandler_when_masterKeySent() throws Exception {
        ResponseEntity<String> response = call(HttpMethod.GET, PROBE_URL, MASTER_KEY, null);

        assertThat(response.getStatusCode()).as("body: %s", response.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(probeValue(response)).isEqualTo("catalog-reached");
    }

    @Test
    @DisplayName("missing key / wrong key on the catalog prefix → 401")
    void should_return401_when_catalogPathCalledWithoutValidKey() throws Exception {
        ResponseEntity<String> missing = call(HttpMethod.GET, PROBE_URL, null, null);
        ResponseEntity<String> wrong = call(HttpMethod.GET, PROBE_URL, CATALOG_KEY + "x", null);

        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertUnauthorizedBody(wrong);
    }
}
