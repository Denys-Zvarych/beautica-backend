package com.beautica.user;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.SelfRegistrationRole;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.RegisterRequest;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.AfterEach;
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

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestSecurityConfig.class)
@DisplayName("User endpoints — integration")
class UserControllerIT extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(UserControllerIT.class);

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ── setup ─────────────────────────────────────────────────────────────────
    // JDK HttpURLConnection rejects PATCH as an invalid method. Replace the
    // default SimpleClientHttpRequestFactory with Apache HttpClient 5, which
    // supports all HTTP methods including PATCH.
    @BeforeEach
    void configureHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
    }

    // ── cleanup ───────────────────────────────────────────────────────────────
    // @Transactional is silently ignored on lifecycle callbacks in RANDOM_PORT tests.
    // Use JdbcTemplate to delete rows in dependency order.
    @AfterEach
    void cleanUp() {
        jdbcTemplate.execute("DELETE FROM refresh_tokens");
        jdbcTemplate.execute("DELETE FROM users");
    }

    // ── GET /api/v1/users/me ──────────────────────────────────────────────────

    @Test
    @DisplayName("GET /me — 401 when no Authorization header")
    void should_return401_when_noTokenProvided() {
        log.debug("Arrange: no Authorization header prepared");

        log.debug("Act: GET /api/v1/users/me without credentials — unauthenticated request must be rejected");
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/users/me", String.class);

        assertThat(response.getStatusCode())
                .as("status must be 401 when no Authorization header is present on GET /me")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("GET /me — 200 with correct profile when valid token provided")
    void should_return200WithProfile_when_validTokenProvided() throws Exception {
        log.debug("Arrange: register user and obtain access token");
        String accessToken = registerAndGetToken(
                "getme@beautica.com", "password123", "Olena", "Koval", null);

        HttpHeaders headers = bearerHeaders(accessToken);

        log.debug("Act: GET /api/v1/users/me with valid token for getme@beautica.com");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users/me",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);

        assertThat(response.getStatusCode())
                .as("status must be 200 when a valid token is used to access own profile")
                .isEqualTo(HttpStatus.OK);

        var apiResponse = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<UserProfileResponse>>() {});
        assertThat(apiResponse.success()).isTrue();
        assertThat(apiResponse.data().email()).isEqualTo("getme@beautica.com");
        assertThat(apiResponse.data().role()).isEqualTo("CLIENT");
        assertThat(apiResponse.data().firstName()).isEqualTo("Olena");
        assertThat(apiResponse.data().lastName()).isEqualTo("Koval");
        assertThat(apiResponse.data().isActive()).isTrue();
        assertThat(apiResponse.data().id()).isNotNull();
    }

    @Test
    @DisplayName("GET /me — 200 for any authenticated role (no role restriction on own profile)")
    void should_return200_when_anyAuthenticatedUserAccessesOwnProfile() throws Exception {
        log.debug("Arrange: register as CLIENT — only role auto-assigned on register");
        String accessToken = registerAndGetToken(
                "anyrole@beautica.com", "password123", null, null, null);

        HttpHeaders headers = bearerHeaders(accessToken);

        log.debug("Act: GET /api/v1/users/me with CLIENT token — any authenticated role must be accepted");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users/me",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);

        assertThat(response.getStatusCode())
                .as("status must be 200 — GET /me has no role restriction")
                .isEqualTo(HttpStatus.OK);

        var apiResponse = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<UserProfileResponse>>() {});
        assertThat(apiResponse.success()).isTrue();
        assertThat(apiResponse.data().role()).isNotBlank();
    }

    // ── PATCH /api/v1/users/me ────────────────────────────────────────────────

    @Test
    @DisplayName("PATCH /me — 401 when no Authorization header")
    void should_return401_when_noTokenOnPatch() {
        log.debug("Arrange: no Authorization header prepared");
        var request = new UpdateProfileRequest("Ivan", "Petrenko", null);

        log.debug("Act: PATCH /api/v1/users/me without credentials — unauthenticated request must be rejected");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users/me",
                HttpMethod.PATCH,
                new HttpEntity<>(request),
                String.class);

        assertThat(response.getStatusCode())
                .as("status must be 401 when no Authorization header is present on PATCH /me")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("PATCH /me — 200 and fields updated when valid patch applied")
    void should_return200AndUpdateFields_when_patchApplied() throws Exception {
        log.debug("Arrange: register user with initial name, obtain token");
        String accessToken = registerAndGetToken(
                "patch@beautica.com", "password123", "Stara", "Familiya", "+380671111111");

        var patchRequest = new UpdateProfileRequest("Nova", "Familiya", "+380672222222");
        HttpHeaders headers = bearerHeaders(accessToken);

        log.debug("Act: PATCH /api/v1/users/me changing firstName='Nova', lastName, and phoneNumber");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users/me",
                HttpMethod.PATCH,
                new HttpEntity<>(patchRequest, headers),
                String.class);

        assertThat(response.getStatusCode())
                .as("status must be 200 when valid patch fields are applied")
                .isEqualTo(HttpStatus.OK);

        var apiResponse = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<UserProfileResponse>>() {});
        assertThat(apiResponse.success()).isTrue();
        assertThat(apiResponse.data().firstName()).isEqualTo("Nova");
        assertThat(apiResponse.data().lastName()).isEqualTo("Familiya");
        assertThat(apiResponse.data().phoneNumber()).isEqualTo("+380672222222");
        assertThat(apiResponse.data().email()).isEqualTo("patch@beautica.com");
    }

    @Test
    @DisplayName("PATCH /me — 200 and fields unchanged when all patch fields are null")
    void should_return200AndLeaveFieldsUnchanged_when_allNullPatch() throws Exception {
        log.debug("Arrange: register user with known name, obtain token");
        String accessToken = registerAndGetToken(
                "nullpatch@beautica.com", "password123", "Kept", "Name", "+380633333333");

        var patchRequest = new UpdateProfileRequest(null, null, null);
        HttpHeaders headers = bearerHeaders(accessToken);

        log.debug("Act: PATCH /api/v1/users/me with all-null fields — existing values must be preserved");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users/me",
                HttpMethod.PATCH,
                new HttpEntity<>(patchRequest, headers),
                String.class);

        assertThat(response.getStatusCode())
                .as("status must be 200 even when all patch fields are null")
                .isEqualTo(HttpStatus.OK);

        var apiResponse = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<UserProfileResponse>>() {});
        assertThat(apiResponse.success()).isTrue();
        assertThat(apiResponse.data().firstName()).isEqualTo("Kept");
        assertThat(apiResponse.data().lastName()).isEqualTo("Name");
        assertThat(apiResponse.data().phoneNumber()).isEqualTo("+380633333333");
    }

    // ── PATCH /api/v1/users/me — validation ──────────────────────────────────

    @Test
    @DisplayName("PATCH /me — 400 when firstName is blank (empty string erases name)")
    void should_return400_when_firstNameIsBlank() throws Exception {
        log.debug("Arrange: register user and obtain access token");
        String accessToken = registerAndGetToken(
                "blank-fn@beautica.com", "password123", "Valid", "Name", null);

        var patchRequest = new UpdateProfileRequest("", null, null);
        HttpHeaders headers = bearerHeaders(accessToken);

        log.debug("Act: PATCH /api/v1/users/me with empty string firstName — must be rejected");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users/me",
                HttpMethod.PATCH,
                new HttpEntity<>(patchRequest, headers),
                String.class);

        assertThat(response.getStatusCode())
                .as("status must be 400 when firstName is an empty string")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("PATCH /me — 400 when firstName exceeds 100 characters")
    void should_return400_when_firstNameTooLong() throws Exception {
        log.debug("Arrange: register user and obtain access token");
        String accessToken = registerAndGetToken(
                "long-fn@beautica.com", "password123", "Valid", "Name", null);

        String tooLong = "A".repeat(101);
        var patchRequest = new UpdateProfileRequest(tooLong, null, null);
        HttpHeaders headers = bearerHeaders(accessToken);

        log.debug("Act: PATCH /api/v1/users/me with 101-character firstName — must exceed max length and be rejected");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users/me",
                HttpMethod.PATCH,
                new HttpEntity<>(patchRequest, headers),
                String.class);

        assertThat(response.getStatusCode())
                .as("status must be 400 when firstName exceeds 100 characters")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("PATCH /me — 400 when phoneNumber contains invalid characters")
    void should_return400_when_phoneNumberInvalidFormat() throws Exception {
        log.debug("Arrange: register user and obtain access token");
        String accessToken = registerAndGetToken(
                "bad-phone@beautica.com", "password123", "Valid", "Name", null);

        var patchRequest = new UpdateProfileRequest(null, null, "not-a-phone!@#");
        HttpHeaders headers = bearerHeaders(accessToken);

        log.debug("Act: PATCH /api/v1/users/me with phoneNumber='not-a-phone!@#' — must fail validation");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users/me",
                HttpMethod.PATCH,
                new HttpEntity<>(patchRequest, headers),
                String.class);

        assertThat(response.getStatusCode())
                .as("status must be 400 when phoneNumber contains invalid characters")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Registers a new user and returns the access token from the 201 response.
     * Uses unique emails per test — @AfterEach wipes the table between tests.
     */
    private String registerAndGetToken(
            String email, String password,
            String firstName, String lastName, String phoneNumber) throws Exception {
        var request = new RegisterRequest(email, password, SelfRegistrationRole.CLIENT, firstName, lastName, phoneNumber, null);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/register", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        var body = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
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
