package com.beautica.auth;

import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.auth.dto.RefreshRequest;
import com.beautica.auth.dto.RegisterRequest;
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
    @DisplayName("GET /users/me with expired JWT returns 401")
    void should_return401_when_expiredJwtTokenUsed() throws Exception {
        var email = "security-expired-" + System.nanoTime() + "@beautica.test";
        log.debug("Arrange: register email={}", email);

        var resp = restTemplate.postForEntity("/auth/register",
                new RegisterRequest(email, "password123", null, null, null),
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

        log.debug("Act: GET /users/me with expired JWT");
        ResponseEntity<String> response = restTemplate.exchange(
                "/users/me", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        log.trace("Assert: status=401");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("POST /auth/invite with CLIENT token returns 403")
    void should_return403_when_clientTokenUsedOnOwnerEndpoint() throws Exception {
        var email = "security-rbac-" + System.nanoTime() + "@beautica.test";
        log.debug("Arrange: register CLIENT email={}", email);

        var resp = restTemplate.postForEntity("/auth/register",
                new RegisterRequest(email, "password123", null, null, null),
                String.class);
        var auth = objectMapper.readValue(resp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        String clientToken = auth.data().accessToken();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(clientToken);
        headers.setContentType(MediaType.APPLICATION_JSON);

        // The role check fires before bean validation, so any syntactically valid JSON body works here
        var body = "{\"email\":\"target@beautica.test\",\"salonId\":\"00000000-0000-0000-0000-000000000001\"}";

        log.debug("Act: POST /auth/invite as CLIENT");
        ResponseEntity<String> response = restTemplate.exchange(
                "/auth/invite", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);

        log.trace("Assert: status=403");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("POST /auth/register with extra 'role' field does not elevate privileges")
    void should_notElevateRole_when_extraRoleFieldSentInRegistrationBody() throws Exception {
        var email = "security-mass-" + System.nanoTime() + "@beautica.test";
        log.debug("Arrange: crafting raw JSON body with extra role field for email={}", email);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String requestBody = String.format(
                "{\"email\":\"%s\",\"password\":\"password123\",\"role\":\"SALON_OWNER\"}", email);

        log.debug("Act: POST /auth/register with extra role field");
        ResponseEntity<String> resp = restTemplate.exchange(
                "/auth/register", HttpMethod.POST,
                new HttpEntity<>(requestBody, headers), String.class);

        log.trace("Assert: status=201, role=CLIENT (not SALON_OWNER)");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        var auth = objectMapper.readValue(resp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        assertThat(auth.data().role()).isEqualTo(Role.CLIENT);
    }

    @Test
    @DisplayName("POST /auth/refresh with revoked token (after logout) returns 401")
    void should_return401_when_revokedRefreshTokenReplayedAfterLogout() throws Exception {
        var email = "security-revoked-" + System.nanoTime() + "@beautica.test";
        log.debug("Arrange: register email={}", email);

        restTemplate.postForEntity("/auth/register",
                new RegisterRequest(email, "password123", null, null, null), String.class);

        log.debug("Arrange: login to obtain tokens");
        ResponseEntity<String> loginResp = restTemplate.postForEntity("/auth/login",
                new LoginRequest(email, "password123"), String.class);
        var loginBody = objectMapper.readValue(loginResp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        String accessToken = loginBody.data().accessToken();
        String refreshToken = loginBody.data().refreshToken();

        log.debug("Arrange: logout to revoke refresh tokens");
        HttpHeaders logoutHeaders = new HttpHeaders();
        logoutHeaders.setBearerAuth(accessToken);
        restTemplate.exchange("/auth/logout", HttpMethod.POST,
                new HttpEntity<>(logoutHeaders), Void.class);

        log.debug("Act: attempt to use the now-revoked refresh token");
        ResponseEntity<String> replayResp = restTemplate.postForEntity(
                "/auth/refresh",
                new RefreshRequest(refreshToken),
                String.class);

        log.trace("Assert: status=401");
        assertThat(replayResp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        var body = objectMapper.readValue(replayResp.getBody(), new TypeReference<ApiResponse<Void>>() {});
        assertThat(body.success()).isFalse();
    }
}
