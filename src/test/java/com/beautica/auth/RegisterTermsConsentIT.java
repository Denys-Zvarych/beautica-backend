package com.beautica.auth;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.beautica.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.beautica.auth.TermsConsentExpectedCopy.PRIVACY_VERSION_REQUIRED;
import static com.beautica.auth.TermsConsentExpectedCopy.TERMS_REQUIRED;
import static com.beautica.auth.TermsConsentExpectedCopy.TERMS_VERSION_REQUIRED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 442 — terms + privacy consent on the three account-creating endpoints. Parameterised over
 * {@link Endpoint}; the flag-{@code true} matrix lives in {@link RequiredMode} (its own context).
 * ITs run on the system clock, so "the server stamps the time" is proven by bracketing the request
 * between two instants and by ignoring a client-supplied {@code termsAcceptedAt}.
 */
@DisplayName("Phase 442 — terms consent at registration / invite accept")
class RegisterTermsConsentIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "Str0ngP@ss1!";
    private static final String TERMS_VERSION = "2026-11-07.2";
    private static final String PRIVACY_VERSION = "2026-10-20";

    /** Consent property names that must never appear in a response body or response schema (D6/D8). */
    private static final Set<String> CONSENT_KEYS =
            Set.of("termsAccepted", "termsAcceptedAt", "termsVersion", "privacyPolicyVersion");

    enum Endpoint {
        REGISTER_CLIENT(HttpStatus.OK, "CLIENT"),
        REGISTER_INDEPENDENT_MASTER(HttpStatus.OK, "INDEPENDENT_MASTER"),
        INVITE_ACCEPT(HttpStatus.CREATED, "SALON_MASTER");

        final HttpStatus success;
        final String role;

        Endpoint(HttpStatus success, String role) {
            this.success = success;
            this.role = role;
        }
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    // ── tolerant mode (default flag = false) ──────────────────────────────────────────────────────

    @ParameterizedTest
    @EnumSource(Endpoint.class)
    @DisplayName("full consent — server-clock timestamp + both versions stored verbatim; client termsAcceptedAt ignored")
    void should_persistServerStampedTriple_when_fullConsentSent(Endpoint endpoint) {
        // Arrange
        var request = newRequest(endpoint);
        request.body().putAll(fullConsent());
        request.body().put("termsAcceptedAt", "2000-01-01T00:00:00Z");
        Instant before = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        // Act
        var response = post(request);

        // Assert
        Instant after = Instant.now().plusMillis(1);
        assertThat(response.getStatusCode()).as("body %s", response.getBody()).isEqualTo(endpoint.success);
        Map<String, Object> row = consentRow(request.email());
        Instant stamped = ((Timestamp) row.get("terms_accepted_at")).toInstant();
        assertThat(stamped).isBetween(before, after);
        assertThat(row.get("terms_version")).isEqualTo(TERMS_VERSION);
        assertThat(row.get("privacy_policy_version")).isEqualTo(PRIVACY_VERSION);
    }

    @ParameterizedTest
    @EnumSource(Endpoint.class)
    @DisplayName("legacy build (no consent fields), flag false — account created, all three columns NULL")
    void should_createUserWithNullTriple_when_consentAbsentAndFlagFalse(Endpoint endpoint) {
        // Arrange
        var request = newRequest(endpoint);

        // Act
        var response = post(request);

        // Assert
        assertThat(response.getStatusCode()).as("body %s", response.getBody()).isEqualTo(endpoint.success);
        Map<String, Object> row = consentRow(request.email());
        assertThat(row.get("terms_accepted_at")).isNull();
        assertThat(row.get("terms_version")).isNull();
        assertThat(row.get("privacy_policy_version")).isNull();
    }

    @ParameterizedTest
    @EnumSource(Endpoint.class)
    @DisplayName("termsAccepted=false with full versions, flag false — 400 with the terms copy, no account")
    void should_return400_when_termsAcceptedFalse(Endpoint endpoint) throws Exception {
        var errors = assertRejected(endpoint, Map.of("termsAccepted", false, "termsVersion", TERMS_VERSION,
                "privacyPolicyVersion", PRIVACY_VERSION), "termsAccepted");

        assertThat(errors.path("termsAccepted").asText()).isEqualTo(TERMS_REQUIRED);
    }

    @ParameterizedTest
    @EnumSource(Endpoint.class)
    @DisplayName("only termsAccepted=false sent, flag false — 400 (a present field ends the tolerant window)")
    void should_return400_when_onlyTermsAcceptedFalseSent(Endpoint endpoint) throws Exception {
        assertRejected(endpoint, Map.of("termsAccepted", false), "termsAccepted");
    }

    @ParameterizedTest
    @EnumSource(Endpoint.class)
    @DisplayName("accepted but terms version missing — 400 on termsVersion with the required copy")
    void should_return400_when_termsVersionMissing(Endpoint endpoint) throws Exception {
        var errors = assertRejected(endpoint, Map.of("termsAccepted", true, "privacyPolicyVersion", PRIVACY_VERSION),
                "termsVersion");

        assertThat(errors.path("termsVersion").asText()).isEqualTo(TERMS_VERSION_REQUIRED);
    }

    @ParameterizedTest
    @EnumSource(Endpoint.class)
    @DisplayName("accepted but privacy version missing — 400 on privacyPolicyVersion with the required copy")
    void should_return400_when_privacyPolicyVersionMissing(Endpoint endpoint) throws Exception {
        var errors = assertRejected(endpoint, Map.of("termsAccepted", true, "termsVersion", TERMS_VERSION),
                "privacyPolicyVersion");

        assertThat(errors.path("privacyPolicyVersion").asText()).isEqualTo(PRIVACY_VERSION_REQUIRED);
    }

    @ParameterizedTest
    @EnumSource(Endpoint.class)
    @DisplayName("versions sent without termsAccepted — 400 on termsAccepted")
    void should_return400_when_termsAcceptedMissingButVersionsPresent(Endpoint endpoint) throws Exception {
        var errors = assertRejected(endpoint,
                Map.of("termsVersion", TERMS_VERSION, "privacyPolicyVersion", PRIVACY_VERSION), "termsAccepted");

        assertThat(errors.path("termsAccepted").asText()).isEqualTo(TERMS_REQUIRED);
    }

    @ParameterizedTest(name = "terms/privacy version \"{0}\" is rejected")
    @ValueSource(strings = {"1.8", "2026-13-01", "2026-11-07.1234", "2026-11-07.1111111111111111111111", ""})
    @DisplayName("malformed version (bad shape, bad month, 4-digit suffix, 33 chars, blank) — 400")
    void should_return400_when_versionFormatInvalid(String bad) throws Exception {
        assertRejected(Endpoint.REGISTER_CLIENT,
                Map.of("termsAccepted", true, "termsVersion", bad, "privacyPolicyVersion", PRIVACY_VERSION),
                "termsVersion");
        assertRejected(Endpoint.REGISTER_CLIENT,
                Map.of("termsAccepted", true, "termsVersion", TERMS_VERSION, "privacyPolicyVersion", bad),
                "privacyPolicyVersion");
    }

    @Test
    @DisplayName("terms version dated in the future — 400 (@PolicyVersionDate)")
    void should_return400_when_versionDateInFuture() throws Exception {
        String future = LocalDate.now().plusDays(3).toString();

        assertRejected(Endpoint.REGISTER_INDEPENDENT_MASTER,
                Map.of("termsAccepted", true, "termsVersion", future, "privacyPolicyVersion", PRIVACY_VERSION),
                "termsVersion");
    }

    @Test
    @DisplayName("V232 CHECK — a partial triple written straight to users is rejected; the full triple is accepted")
    void should_rejectPartialTriple_when_writtenDirectlyToUsers() {
        // Arrange
        var request = newRequest(Endpoint.REGISTER_CLIENT);
        assertThat(post(request).getStatusCode()).isEqualTo(HttpStatus.OK);

        // Act + Assert — ck_users_terms_consent_triple (V232)
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE users SET terms_accepted_at = NOW() WHERE email = ?", request.email()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_users_terms_consent_triple");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE users SET terms_accepted_at = NOW(), terms_version = '2026-11-07' WHERE email = ?",
                request.email()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbcTemplate.update("UPDATE users SET terms_accepted_at = NOW(), terms_version = '2026-11-07', "
                + "privacy_policy_version = '2026-10-20' WHERE email = ?", request.email())).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(Endpoint.class)
    @DisplayName("D8 — the account-creation response carries no consent property")
    void should_omitConsentFromResponse_when_fullConsentSent(Endpoint endpoint) throws Exception {
        // Arrange
        var request = newRequest(endpoint);
        request.body().putAll(fullConsent());

        // Act
        var response = post(request);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(endpoint.success);
        assertThat(fieldNames(objectMapper.readTree(response.getBody())))
                .as("response of %s", endpoint).doesNotContainAnyElementsOf(CONSENT_KEYS);
    }

    @Test
    @DisplayName("D6 — GET /users/me never exposes the stored consent")
    void should_omitConsentFromProfile_when_consentStored() throws Exception {
        // Arrange
        var request = newRequest(Endpoint.INVITE_ACCEPT);
        request.body().putAll(fullConsent());
        String accessToken = accessToken(post(request));

        // Act
        var me = restTemplate.exchange("/api/v1/users/me", HttpMethod.GET,
                new HttpEntity<>(bearer(accessToken)), String.class);

        // Assert
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(me.getBody());
        assertThat(body.path("data").path("email").asText()).as("profile really loaded").isEqualTo(request.email());
        assertThat(fieldNames(body)).doesNotContainAnyElementsOf(CONSENT_KEYS);
    }

    @Test
    @DisplayName("D7 — a later profile update (even one smuggling consent fields) leaves the stored triple untouched")
    void should_keepConsentTriple_when_profileUpdatedAfterRegistration() {
        // Arrange
        var request = newRequest(Endpoint.INVITE_ACCEPT);
        request.body().putAll(fullConsent());
        String accessToken = accessToken(post(request));
        Map<String, Object> stored = consentRow(request.email());
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("firstName", "Iryna");
        patch.put("termsAccepted", false);
        patch.put("termsVersion", "2020-01-01");
        patch.put("privacyPolicyVersion", "2020-01-01");
        patch.put("termsAcceptedAt", "2000-01-01T00:00:00Z");

        // Act
        var response = restTemplate.exchange("/api/v1/users/me", HttpMethod.PATCH,
                new HttpEntity<>(patch, bearer(accessToken)), String.class);

        // Assert
        assertThat(response.getStatusCode()).as("body %s", response.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(jdbcTemplate.queryForObject("SELECT first_name FROM users WHERE email = ?", String.class,
                request.email())).as("the update itself was applied").isEqualTo("Iryna");
        assertThat(consentRow(request.email())).isEqualTo(stored);
    }

    @ParameterizedTest
    @EnumSource(Endpoint.class)
    @DisplayName("D4 — the legacy path logs register.consent.legacy with the role only, no PII")
    void should_logRoleOnly_when_legacyRequestAccepted(Endpoint endpoint) {
        // Arrange
        var request = newRequest(endpoint);

        // Act
        List<ILoggingEvent> events = captureConsentLog(() -> post(request));

        // Assert
        assertThat(events).extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("register.consent.legacy role=" + endpoint.role);
        assertThat(events.get(0).getFormattedMessage())
                .doesNotContain(request.email(), "Olena", "Shevchenko", "+380501234567");
    }

    @ParameterizedTest
    @EnumSource(Endpoint.class)
    @DisplayName("D4 — a full-consent account emits no legacy log line")
    void should_notLogLegacy_when_fullConsentSent(Endpoint endpoint) {
        // Arrange
        var request = newRequest(endpoint);
        request.body().putAll(fullConsent());

        // Act
        List<ILoggingEvent> events = captureConsentLog(() -> post(request));

        // Assert
        assertThat(consentRow(request.email()).get("terms_version")).isEqualTo(TERMS_VERSION);
        assertThat(events).isEmpty();
    }

    // ── OpenAPI contract (D8 / acceptance: request schemas change, response schemas do not) ──────

    @Nested
    @TestPropertySource(properties = {
            "springdoc.api-docs.enabled=true",
            "springdoc.api-docs.path=/api-docs"
    })
    @DisplayName("/api-docs — consent fields are optional request properties and never in a response")
    class ApiDocs {

        @Autowired
        private TestRestTemplate docsRestTemplate;

        @ParameterizedTest(name = "{0}.{1} is an optional {2}")
        @CsvSource({
                "RegisterRequest, termsAccepted, boolean",
                "RegisterRequest, termsVersion, string",
                "RegisterRequest, privacyPolicyVersion, string",
                "RegisterIndependentMasterRequest, termsAccepted, boolean",
                "RegisterIndependentMasterRequest, termsVersion, string",
                "RegisterIndependentMasterRequest, privacyPolicyVersion, string",
                "InviteAcceptRequest, termsAccepted, boolean",
                "InviteAcceptRequest, termsVersion, string",
                "InviteAcceptRequest, privacyPolicyVersion, string"
        })
        @DisplayName("each account-creating request schema has the three consent properties, none required")
        void should_exposeOptionalConsentProperty_when_requestSchemaRead(String schema, String field, String type)
                throws Exception {
            JsonNode node = schemas().path(schema);

            assertThat(node.path("properties").path(field).path("type").asText())
                    .as("%s.%s type", schema, field).isEqualTo(type);
            List<String> required = new ArrayList<>();
            node.path("required").forEach(r -> required.add(r.asText()));
            assertThat(required).as("%s required", schema).isNotEmpty().doesNotContain(field);
        }

        @Test
        @DisplayName("no response / non-request schema carries a consent property")
        void should_notExposeConsent_when_anyResponseSchemaRead() throws Exception {
            JsonNode schemas = schemas();
            List<String> leaks = new ArrayList<>();
            schemas.fields().forEachRemaining(entry -> {
                if (entry.getKey().endsWith("Request")) {
                    return;
                }
                entry.getValue().path("properties").fieldNames().forEachRemaining(name -> {
                    if (CONSENT_KEYS.contains(name)) {
                        leaks.add(entry.getKey() + "." + name);
                    }
                });
            });

            assertThat(schemas.has("AuthResponse")).as("schemas really loaded").isTrue();
            assertThat(leaks).isEmpty();
        }

        private JsonNode schemas() throws Exception {
            ResponseEntity<String> response = docsRestTemplate.getForEntity("/api-docs", String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            return objectMapper.readTree(response.getBody()).path("components").path("schemas");
        }
    }

    // ── strict mode (flag = true) ────────────────────────────────────────────────────────────────

    @Nested
    @TestPropertySource(properties = "app.auth.require-terms-consent=true")
    @DisplayName("app.auth.require-terms-consent=true")
    class RequiredMode {

        // Own injection point: this nested class runs in its own context (own port); never reuse the
        // enclosing instance's template, which may be bound to the flag-false server.
        @Autowired
        private TestRestTemplate strictRestTemplate;

        @ParameterizedTest
        @EnumSource(Endpoint.class)
        @DisplayName("consent absent, flag true — 400 naming all three fields, no account")
        void should_return400AndCreateNoUser_when_consentAbsent(Endpoint endpoint) throws Exception {
            var errors = assertRejected(strictRestTemplate, endpoint, Map.of(), "termsAccepted");

            assertThat(errors.path("termsAccepted").asText()).isEqualTo(TERMS_REQUIRED);
            assertThat(errors.path("termsVersion").asText()).isEqualTo(TERMS_VERSION_REQUIRED);
            assertThat(errors.path("privacyPolicyVersion").asText()).isEqualTo(PRIVACY_VERSION_REQUIRED);
        }

        @ParameterizedTest
        @EnumSource(Endpoint.class)
        @DisplayName("termsAccepted=false, flag true — 400")
        void should_return400_when_termsAcceptedFalse(Endpoint endpoint) throws Exception {
            assertRejected(strictRestTemplate, endpoint, Map.of("termsAccepted", false, "termsVersion", TERMS_VERSION,
                    "privacyPolicyVersion", PRIVACY_VERSION), "termsAccepted");
        }

        @ParameterizedTest
        @EnumSource(Endpoint.class)
        @DisplayName("full consent, flag true — account created with the triple")
        void should_persistTriple_when_fullConsentSent(Endpoint endpoint) {
            // Arrange
            var request = newRequest(endpoint);
            request.body().putAll(fullConsent());

            // Act
            var response = post(strictRestTemplate, request);

            // Assert
            assertThat(response.getStatusCode()).as("body %s", response.getBody()).isEqualTo(endpoint.success);
            assertThat(consentRow(request.email()).get("terms_version")).isEqualTo(TERMS_VERSION);
            assertThat(consentRow(request.email()).get("terms_accepted_at")).isNotNull();
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private record Request(String url, String email, Map<String, Object> body) { }

    private JsonNode assertRejected(Endpoint endpoint, Map<String, Object> consent, String errorField)
            throws Exception {
        return assertRejected(restTemplate, endpoint, consent, errorField);
    }

    /** Posts, asserts 400 + {@code errors.<errorField>} + no user row; returns the {@code errors} node. */
    private JsonNode assertRejected(TestRestTemplate template, Endpoint endpoint, Map<String, Object> consent,
                                String errorField) throws Exception {
        var request = newRequest(endpoint);
        request.body().putAll(consent);

        var response = post(template, request);

        assertThat(response.getStatusCode()).as("%s %s", endpoint, consent).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("errors").has(errorField)).as("errors of %s: %s", consent, body).isTrue();
        assertThat(countUsers(request.email())).isZero();
        return body.path("errors");
    }

    private List<ILoggingEvent> captureConsentLog(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(TermsConsentStamp.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list;
    }

    private String accessToken(ResponseEntity<String> inviteAcceptResponse) {
        assertThat(inviteAcceptResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        try {
            return objectMapper.readTree(inviteAcceptResponse.getBody()).path("data").path("accessToken").asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpHeaders bearer(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        return headers;
    }

    /** Every object field name anywhere in the tree. */
    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new java.util.HashSet<>();
        collectFieldNames(node, names);
        return names;
    }

    private static void collectFieldNames(JsonNode node, Set<String> names) {
        node.fields().forEachRemaining(entry -> {
            names.add(entry.getKey());
            collectFieldNames(entry.getValue(), names);
        });
        node.elements().forEachRemaining(child -> {
            if (child.isContainerNode() && !node.isObject()) {
                collectFieldNames(child, names);
            }
        });
    }

    private ResponseEntity<String> post(Request request) {
        return post(restTemplate, request);
    }

    private static ResponseEntity<String> post(TestRestTemplate template, Request request) {
        return template.postForEntity(request.url(), request.body(), String.class);
    }

    private static Map<String, Object> fullConsent() {
        return Map.of("termsAccepted", true, "termsVersion", TERMS_VERSION, "privacyPolicyVersion", PRIVACY_VERSION);
    }

    private Request newRequest(Endpoint endpoint) {
        String email = "consent-" + UUID.randomUUID() + "@beautica.test";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("password", PASSWORD);
        body.put("firstName", "Olena");
        body.put("lastName", "Shevchenko");
        body.put("phoneNumber", "+380501234567");
        return switch (endpoint) {
            case REGISTER_CLIENT -> {
                body.put("email", email);
                body.put("role", "CLIENT");
                yield new Request("/api/v1/auth/register", email, body);
            }
            case REGISTER_INDEPENDENT_MASTER -> {
                body.put("email", email);
                yield new Request("/api/v1/auth/register/independent-master", email, body);
            }
            case INVITE_ACCEPT -> {
                body.put("token", seedInvite(email));
                yield new Request("/api/v1/auth/invite/accept", email, body);
            }
        };
    }

    /** One acceptable SALON_MASTER invite for {@code email}; returns the raw token. */
    private String seedInvite(String email) {
        UUID ownerId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                        + "VALUES (?, ?, ?, 'SALON_OWNER', true, true)",
                ownerId, "owner-" + ownerId + "@beautica.test", passwordEncoder.encode(PASSWORD));
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at, city_id) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW(), ?)",
                salonId, ownerId, "Consent Salon " + salonId, testCityId());
        String rawToken = "raw-consent-" + UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO invite_tokens (id, token, email, salon_id, role, expires_at, is_used, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, 'SALON_MASTER', ?, false, NOW(), NOW())",
                UUID.randomUUID(), sha256Hex(rawToken), email, salonId,
                Timestamp.from(Instant.now().plus(2, ChronoUnit.DAYS)));
        return rawToken;
    }

    private Map<String, Object> consentRow(String email) {
        return jdbcTemplate.queryForMap(
                "SELECT terms_accepted_at, terms_version, privacy_policy_version FROM users WHERE email = ?", email);
    }

    private Integer countUsers(String email) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, email);
    }

    private static String sha256Hex(String input) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
