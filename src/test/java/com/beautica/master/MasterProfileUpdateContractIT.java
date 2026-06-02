package com.beautica.master;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-stack contract test for {@code PATCH /api/v1/independent-masters/me/profile}.
 *
 * <h2>Why this test exists (the bug it would have caught)</h2>
 * The mobile edit-profile screen sends {@code instagram: ""} to CLEAR a stored
 * handle. The old {@code @Pattern} on {@link com.beautica.master.dto.MasterProfileUpdateRequest}
 * rejected the empty string → HTTP 400. Because a single Bean Validation failure
 * rejects the WHOLE body, {@code firstName}/{@code lastName} were silently never
 * persisted — a dead Save button with no error surfaced to the user.
 *
 * <p>Every prior test for this endpoint either:
 * <ul>
 *   <li>stubbed {@code UserService.updateMasterProfile} (the {@code @WebMvcTest}
 *       slice {@code IndependentMasterProfileUpdateTest}) — so the real Bean
 *       Validation + real persistence never ran, or</li>
 *   <li>exercised the service in isolation with a mocked repository.</li>
 * </ul>
 * Neither could observe that the name fields actually reached Postgres. This test
 * boots the full context against Testcontainers Postgres and drives the REAL chain
 * (HTTP → real Bean Validation → real {@code UserService} → real Postgres) and then
 * re-reads the row FROM THE DATABASE — the read-back is the crux the slice could not do.
 */
@Import(TestSecurityConfig.class)
@DisplayName("PATCH /me/profile — clear-field contract (full stack, real Postgres)")
class MasterProfileUpdateContractIT extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(MasterProfileUpdateContractIT.class);

    private static final String PATCH_PROFILE_URL = "/api/v1/independent-masters/me/profile";
    private static final String TEST_PASSWORD = "Str0ngP@ss1!";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    // JDK HttpURLConnection rejects PATCH as an invalid method. Replace the default
    // request factory with Apache HttpClient 5, which supports all HTTP methods.
    @BeforeEach
    void configureHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
    }

    // ── CRITICAL regression — clear-field persists names to the DB ────────────

    @Test
    @DisplayName("PATCH /me/profile — 200 AND firstName/lastName PERSISTED to Postgres when instagram=\"\" clears the handle")
    void should_persistNamesAndClearInstagram_when_exactFailingClearPayloadSent() throws Exception {
        log.debug("Arrange: seed an INDEPENDENT_MASTER with a known name and a NON-empty instagram handle");
        UUID userId = UUID.randomUUID();
        seedIndependentMaster(userId, "clear-field@beautica.test",
                "Stara", "Familiya", "@old_handle", "Old bio");
        String token = login("clear-field@beautica.test");

        // The EXACT payload proven to fail live: names changed, bio + instagram cleared via "".
        var body = new LinkedHashMap<String, Object>();
        body.put("firstName", "Olena");
        body.put("lastName", "Koval");
        body.put("bio", "");
        body.put("instagram", "");

        log.debug("Act: PATCH /me/profile with {{firstName, lastName, bio:\"\", instagram:\"\"}} — the previously-failing clear payload");
        ResponseEntity<String> response = restTemplate.exchange(
                PATCH_PROFILE_URL, HttpMethod.PATCH,
                new HttpEntity<>(objectMapper.writeValueAsString(body), bearerHeaders(token)),
                String.class);

        assertThat(response.getStatusCode())
                .as("clear-field payload (instagram=\"\") must be accepted with 200, body=%s", response.getBody())
                .isEqualTo(HttpStatus.OK);

        // ── crux: re-read the row FROM THE DATABASE (not the echoed response) ──
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT first_name, last_name, bio, instagram FROM users WHERE id = ?", userId);

        assertThat(row.get("first_name"))
                .as("firstName must be PERSISTED to Postgres — this is the field the bug silently dropped")
                .isEqualTo("Olena");
        assertThat(row.get("last_name"))
                .as("lastName must be PERSISTED to Postgres")
                .isEqualTo("Koval");
        assertThat((String) row.get("instagram"))
                .as("instagram must be cleared (empty) in Postgres, actual=%s", row.get("instagram"))
                .isNullOrEmpty();
        assertThat((String) row.get("bio"))
                .as("bio must be cleared (empty) in Postgres, actual=%s", row.get("bio"))
                .isNullOrEmpty();
    }

    // ── malformed instagram still rejected, with a populated top-level errors map ─

    @Test
    @DisplayName("PATCH /me/profile — 400 with errors.instagram populated when instagram is malformed (names NOT persisted)")
    void should_return400WithErrorsMap_when_instagramMalformed() throws Exception {
        log.debug("Arrange: seed an INDEPENDENT_MASTER with a known name");
        UUID userId = UUID.randomUUID();
        seedIndependentMaster(userId, "bad-ig@beautica.test",
                "Keep", "Me", null, null);
        String token = login("bad-ig@beautica.test");

        var body = new LinkedHashMap<String, Object>();
        body.put("firstName", "Olena");
        body.put("lastName", "Koval");
        body.put("instagram", "bad handle!!");   // contains a space — fails the allowlist pattern

        log.debug("Act: PATCH /me/profile with a malformed instagram — a real format violation must still be rejected");
        ResponseEntity<String> response = restTemplate.exchange(
                PATCH_PROFILE_URL, HttpMethod.PATCH,
                new HttpEntity<>(objectMapper.writeValueAsString(body), bearerHeaders(token)),
                String.class);

        assertThat(response.getStatusCode())
                .as("a malformed instagram must produce a clean 400, body=%s", response.getBody())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        var apiResponse = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<Object>>() {});
        assertThat(apiResponse.success())
                .as("error envelope must report success=false")
                .isFalse();
        assertThat(apiResponse.errors())
                .as("the top-level errors map must be populated so the mobile ErrorMapperInterceptor can render the field message")
                .isNotNull()
                .containsKey("instagram");
        assertThat(apiResponse.errors().get("instagram"))
                .as("the instagram entry must carry a non-blank validation message")
                .isNotBlank();

        // The whole body was rejected — the valid name fields must NOT have leaked into Postgres.
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT first_name, last_name FROM users WHERE id = ?", userId);
        assertThat(row.get("first_name"))
                .as("a rejected request must not persist any field — firstName stays at its seeded value")
                .isEqualTo("Keep");
        assertThat(row.get("last_name"))
                .as("a rejected request must not persist any field — lastName stays at its seeded value")
                .isEqualTo("Me");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Seeds an email-verified {@code INDEPENDENT_MASTER} directly (the role cannot
     * self-register) with a real BCrypt hash via the production {@link PasswordEncoder}
     * (§M — no fake password hashes).
     */
    private void seedIndependentMaster(UUID id, String email, String firstName,
            String lastName, String instagram, String bio) {
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, first_name, last_name, "
                        + "instagram, bio, is_active, email_verified) "
                        + "VALUES (?, ?, ?, 'INDEPENDENT_MASTER', ?, ?, ?, ?, true, true)",
                id, email, passwordEncoder.encode(TEST_PASSWORD), firstName, lastName, instagram, bio);
    }

    /** Logs in the seeded user and returns a fresh access token. */
    private String login(String email) throws Exception {
        ResponseEntity<String> loginResp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, TEST_PASSWORD), String.class);
        assertThat(loginResp.getStatusCode())
                .as("seeded INDEPENDENT_MASTER must be able to log in, body=%s", loginResp.getBody())
                .isEqualTo(HttpStatus.OK);
        var body = objectMapper.readValue(
                loginResp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        return body.data().accessToken();
    }

    /** Builds HttpHeaders with a Bearer token and JSON content-type. */
    private HttpHeaders bearerHeaders(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
