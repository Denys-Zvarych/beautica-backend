package com.beautica.auth;

import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.auth.dto.RefreshRequest;
import com.beautica.auth.dto.RegisterRequest;
import com.beautica.auth.dto.SelfRegistrationRole;
import com.beautica.common.ApiResponse;
import com.beautica.config.JwtConfig;
import com.beautica.config.TestSecurityConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@Import(TestSecurityConfig.class)
@DisplayName("Auth — security regression")
class AuthSecurityTest {

    private static final Logger log = LoggerFactory.getLogger(AuthSecurityTest.class);

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtConfig jwtConfig;

    @Test
    @DisplayName("GET /api/v1/users/me with expired JWT returns 401")
    void should_return401_when_expiredJwtTokenUsed() throws Exception {
        var email = "security-expired-" + System.nanoTime() + "@beautica.test";
        log.debug("Arrange: register email={}", email);

        var resp = restTemplate.postForEntity("/api/v1/auth/register",
                new RegisterRequest(email, "password123", SelfRegistrationRole.CLIENT, null, null, null, null),
                String.class);
        var auth = objectMapper.readValue(resp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        var userId = auth.data().userId();

        // Build a JwtTokenProvider with 1 ms access token expiry using the same secret
        var shortLivedConfig = new JwtConfig(jwtConfig.secret(), 1L, jwtConfig.refreshTokenExpiration());
        var shortJwtProvider = new JwtTokenProvider(shortLivedConfig);
        String expiredToken = shortJwtProvider.generateAccessToken(userId, email, Role.CLIENT);

        // Guarantee expiry before the request reaches the filter
        Thread.sleep(10);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(expiredToken);

        log.debug("Act: GET /api/v1/users/me with expired JWT for userId={}", userId);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode())
                .as("status must be 401 for expired JWT")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("POST /auth/invite with CLIENT token returns 403")
    void should_return403_when_clientTokenUsedOnOwnerEndpoint() throws Exception {
        var email = "security-rbac-" + System.nanoTime() + "@beautica.test";
        log.debug("Arrange: register CLIENT email={}", email);

        var resp = restTemplate.postForEntity("/api/v1/auth/register",
                new RegisterRequest(email, "password123", SelfRegistrationRole.CLIENT, null, null, null, null),
                String.class);
        var auth = objectMapper.readValue(resp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        String clientToken = auth.data().accessToken();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(clientToken);
        headers.setContentType(MediaType.APPLICATION_JSON);

        // The role check fires before bean validation, so any syntactically valid JSON body works here
        var body = "{\"email\":\"target@beautica.test\",\"salonId\":\"00000000-0000-0000-0000-000000000001\"}";

        log.debug("Act: POST /auth/invite as CLIENT role (should be denied — owner-only endpoint)");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/auth/invite", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);

        assertThat(response.getStatusCode())
                .as("CLIENT must receive 403 on owner-only invite endpoint")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("POST /auth/register with role=SALON_OWNER and no businessName returns 400")
    void should_return400_when_salonOwnerRegistersWithoutBusinessName() throws Exception {
        var email = "security-mass-" + System.nanoTime() + "@beautica.test";
        log.debug("Arrange: crafting raw JSON body with role=SALON_OWNER and no businessName for email={}", email);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String requestBody = String.format(
                "{\"email\":\"%s\",\"password\":\"password123\",\"role\":\"SALON_OWNER\"}", email);

        log.debug("Act: POST /auth/register with SALON_OWNER role and absent businessName field");
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/auth/register", HttpMethod.POST,
                new HttpEntity<>(requestBody, headers), String.class);

        assertThat(resp.getStatusCode())
                .as("status must be 400 when SALON_OWNER registers without businessName")
                .isEqualTo(HttpStatus.BAD_REQUEST);

        var body = objectMapper.readValue(resp.getBody(), new TypeReference<ApiResponse<Void>>() {});
        assertThat(body.success()).isFalse();
        assertThat(body.message()).containsIgnoringCase("businessName");
    }

    @Test
    @DisplayName("POST /auth/refresh with revoked token (after logout) returns 401")
    void should_return401_when_revokedRefreshTokenReplayedAfterLogout() throws Exception {
        var email = "security-revoked-" + System.nanoTime() + "@beautica.test";
        log.debug("Arrange: register email={}", email);

        restTemplate.postForEntity("/api/v1/auth/register",
                new RegisterRequest(email, "password123", SelfRegistrationRole.CLIENT, null, null, null, null), String.class);

        log.debug("Arrange: login to obtain tokens");
        ResponseEntity<String> loginResp = restTemplate.postForEntity("/api/v1/auth/login",
                new LoginRequest(email, "password123"), String.class);
        var loginBody = objectMapper.readValue(loginResp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        String accessToken = loginBody.data().accessToken();
        String refreshToken = loginBody.data().refreshToken();

        log.debug("Arrange: logout to revoke refresh tokens");
        HttpHeaders logoutHeaders = new HttpHeaders();
        logoutHeaders.setBearerAuth(accessToken);
        restTemplate.exchange("/api/v1/auth/logout", HttpMethod.POST,
                new HttpEntity<>(logoutHeaders), Void.class);

        log.debug("Act: attempt to use the revoked refresh token after logout, email={}", email);
        ResponseEntity<String> replayResp = restTemplate.postForEntity(
                "/api/v1/auth/refresh",
                new RefreshRequest(refreshToken),
                String.class);

        assertThat(replayResp.getStatusCode())
                .as("status must be 401 when replaying a revoked refresh token")
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        var body = objectMapper.readValue(replayResp.getBody(), new TypeReference<ApiResponse<Void>>() {});
        assertThat(body.success()).isFalse();
    }

    @Test
    @DisplayName("Should not leak enum values when invalid enum value is sent")
    void should_notLeakEnumValues_when_invalidEnumValueSent() throws Exception {
        var email = "security-enum-" + System.nanoTime() + "@beautica.test";
        log.debug("Arrange: crafting raw JSON body with an invalid enum value (not in SelfRegistrationRole) for email={}", email);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // INVALID_ENUM_VALUE is not a member of SelfRegistrationRole — Jackson raises
        // InvalidFormatException, which the handler must sanitise before responding.
        String requestBody = String.format(
                "{\"email\":\"%s\",\"password\":\"password123\",\"role\":\"INVALID_ENUM_VALUE\"}", email);

        log.debug("Act: POST /api/v1/auth/register with unrecognised enum value");
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/auth/register", HttpMethod.POST,
                new HttpEntity<>(requestBody, headers), String.class);

        assertThat(resp.getStatusCode())
                .as("status must be 400 for an unrecognised enum value")
                .isEqualTo(HttpStatus.BAD_REQUEST);

        String body = resp.getBody();
        assertThat(body)
                .as("response must not expose enum constants or the raw submitted value")
                .doesNotContain("[MANICURE")
                .doesNotContain("[CLIENT")
                .doesNotContain("[SALON_OWNER")
                .doesNotContain("com.beautica")
                .doesNotContain("org.springframework")
                .doesNotContain("INVALID_ENUM_VALUE");
        assertThat(body)
                .as("response must include the exact safe, user-readable hint")
                .containsIgnoringCase("not a recognised option");
    }

    @Test
    @DisplayName("Should not leak framework internals when malformed JSON is sent")
    void should_notLeakInternals_when_malformedJsonSent() throws Exception {
        log.debug("Arrange: crafting syntactically invalid JSON body");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        log.debug("Act: POST /api/v1/auth/register with malformed JSON body");
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/auth/register", HttpMethod.POST,
                new HttpEntity<>("{invalid", headers), String.class);

        assertThat(resp.getStatusCode())
                .as("status must be 400 for malformed JSON")
                .isEqualTo(HttpStatus.BAD_REQUEST);

        String body = resp.getBody();
        assertThat(body)
                .as("response must not expose framework internals or parse-error coordinates")
                .doesNotContain("org.springframework")
                .doesNotContain("line:")
                .doesNotContain("column:");

        var parsed = objectMapper.readValue(body, new TypeReference<ApiResponse<Void>>() {});
        assertThat(parsed.message())
                .as("message must be the exact safe string for non-enum parse errors")
                .isEqualTo("Request body is malformed or missing required fields");
    }

    @Test
    @DisplayName("Should return 415 and safe message when no Content-Type is sent")
    void should_return415_when_noContentTypeSent() throws Exception {
        // Arrange — body is present but headers carry no Content-Type at all.
        // Use byte[] so RestTemplate's ByteArrayHttpMessageConverter is selected instead of
        // StringHttpMessageConverter, which would silently inject Content-Type: text/plain.
        HttpHeaders headers = new HttpHeaders();
        byte[] rawBody = "{\"email\":\"no-content-type@beautica.test\",\"password\":\"password123\",\"role\":\"CLIENT\"}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);

        log.debug("Act: POST /api/v1/auth/register with a body but without Content-Type header");

        // Act
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/auth/register", HttpMethod.POST,
                new HttpEntity<>(rawBody, headers), String.class);

        // Assert — GlobalExceptionHandler#handleUnsupportedMediaType deterministically returns 415.
        assertThat(resp.getStatusCode())
                .as("status must be 415 Unsupported Media Type when Content-Type header is absent")
                .isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);

        String body = resp.getBody();
        log.debug("Received status={} body={}", resp.getStatusCode().value(), body);

        assertThat(body)
                .as("response must not contain Spring package names")
                .doesNotContain("org.springframework");

        assertThat(body)
                .as("response must not contain Beautica internal package names")
                .doesNotContain("com.beautica");

        assertThat(body)
                .as("response must not contain JSON parse-error coordinates 'line:'")
                .doesNotContain("line:");

        assertThat(body)
                .as("response must not contain JSON parse-error coordinates 'column:'")
                .doesNotContain("column:");

        var parsed = objectMapper.readValue(resp.getBody(), new TypeReference<ApiResponse<Void>>() {});
        assertThat(parsed.success()).isFalse();
        assertThat(parsed.message())
                .as("response must carry the exact safe message for missing Content-Type")
                .isEqualTo("Content-Type not supported. Use application/json");
    }

    @Test
    @DisplayName("POST /auth/register with role=SALON_ADMIN returns 400 (type constraint blocks deserialisation)")
    void should_notElevateRole_when_extraRoleFieldSentInRegistrationBody() throws Exception {
        var email = "security-priv-" + System.nanoTime() + "@beautica.test";
        log.debug("Arrange: crafting raw JSON body with role=SALON_ADMIN for email={}", email);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // SALON_ADMIN is not a member of SelfRegistrationRole — Jackson cannot
        // deserialise this value and Spring will return 400 before reaching the service.
        String requestBody = String.format(
                "{\"email\":\"%s\",\"password\":\"password123\",\"role\":\"SALON_ADMIN\"}", email);

        log.debug("Act: POST /auth/register with role=SALON_ADMIN which is not self-registrable");
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/auth/register", HttpMethod.POST,
                new HttpEntity<>(requestBody, headers), String.class);

        assertThat(resp.getStatusCode())
                .as("status must be 400 when role=SALON_ADMIN is sent (not in SelfRegistrationRole enum)")
                .isEqualTo(HttpStatus.BAD_REQUEST);

        String body = resp.getBody();
        var parsed = objectMapper.readValue(body, new TypeReference<ApiResponse<Void>>() {});
        assertThat(parsed.success())
                .as("success must be false when an unpermitted role value is submitted")
                .isFalse();
        assertThat(body)
                .as("response must not echo back the submitted SALON_ADMIN value")
                .doesNotContain("SALON_ADMIN");
    }
}
