package com.beautica.auth;

import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.auth.dto.RefreshRequest;
import com.beautica.auth.dto.RegisterRequest;
import com.beautica.common.ApiResponse;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.beautica.config.TestSecurityConfig;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@Import(TestSecurityConfig.class)
@DisplayName("Auth endpoints — integration")
class AuthControllerIT {

    private static final Logger log = LoggerFactory.getLogger(AuthControllerIT.class);

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void should_return201_when_registerWithValidData() throws Exception {
        var request = new RegisterRequest(
                "register@beautica.com", "password123",
                "Anna", "Test", null);
        log.debug("Arrange: register request for email={}", request.email());

        log.debug("Act: POST /auth/register");
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/register", request, String.class);

        log.trace("Assert: status={}", response.getStatusCode());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        var apiResponse = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        assertThat(apiResponse.success()).isTrue();
        assertThat(apiResponse.data().accessToken()).isNotBlank();
        assertThat(apiResponse.data().refreshToken()).isNotBlank();
        assertThat(apiResponse.data().tokenType()).isEqualTo("Bearer");
        assertThat(apiResponse.data().email()).isEqualTo("register@beautica.com");
        assertThat(apiResponse.data().role()).isEqualTo(com.beautica.auth.Role.CLIENT);
    }

    @Test
    void should_return409_when_registerWithDuplicateEmail() throws Exception {
        log.debug("Arrange: registering email={} twice", "duplicate@beautica.com");
        var request = new RegisterRequest(
                "duplicate@beautica.com", "password123",
                null, null, null);

        restTemplate.postForEntity("/api/v1/auth/register", request, String.class);

        log.debug("Act: POST /auth/register (second attempt)");
        ResponseEntity<String> secondResponse = restTemplate.postForEntity(
                "/api/v1/auth/register", request, String.class);

        log.trace("Assert: status={}", secondResponse.getStatusCode());
        assertThat(secondResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        var apiResponse = objectMapper.readValue(
                secondResponse.getBody(), new TypeReference<ApiResponse<Void>>() {});
        assertThat(apiResponse.success()).isFalse();
        assertThat(apiResponse.message()).isNotBlank();
    }

    @Test
    void should_return200WithTokenPair_when_loginWithValidCredentials() throws Exception {
        var email = "login.valid@beautica.com";
        var password = "mypassword1";
        log.debug("Arrange: pre-registering email={} with known password", email);

        restTemplate.postForEntity("/api/v1/auth/register",
                new RegisterRequest(email, password, null, null, null),
                String.class);

        log.debug("Act: POST /auth/login");
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/login",
                new LoginRequest(email, password),
                String.class);

        log.trace("Assert: status={}, hasTokenPair=true", response.getStatusCode());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        var apiResponse = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        assertThat(apiResponse.success()).isTrue();
        assertThat(apiResponse.data().accessToken()).isNotBlank();
        assertThat(apiResponse.data().refreshToken()).isNotBlank();
        assertThat(apiResponse.data().tokenType()).isEqualTo("Bearer");
        assertThat(apiResponse.data().email()).isEqualTo(email);
    }

    @Test
    void should_return401_when_loginWithBadCredentials() throws Exception {
        log.debug("Arrange: no pre-existing user");

        log.debug("Act: POST /auth/login with unknown email");
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/login",
                new LoginRequest("nonexistent@beautica.com", "wrongpass"),
                String.class);

        log.trace("Assert: status={}", response.getStatusCode());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        var apiResponse = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<Void>>() {});
        assertThat(apiResponse.success()).isFalse();
        assertThat(apiResponse.message()).isNotBlank();
    }

    @Test
    void should_return200WithNewTokenPair_when_refreshWithValidToken() throws Exception {
        var email = "refresh.user@beautica.com";
        var password = "refreshpass1";
        log.debug("Arrange: user {} registered and logged in", email);

        restTemplate.postForEntity("/api/v1/auth/register",
                new RegisterRequest(email, password, null, null, null),
                String.class);

        ResponseEntity<String> loginResp = restTemplate.postForEntity(
                "/api/v1/auth/login",
                new LoginRequest(email, password),
                String.class);

        var loginBody = objectMapper.readValue(
                loginResp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        String originalRefreshToken = loginBody.data().refreshToken();

        log.debug("Act: POST /auth/refresh with original refresh token");
        ResponseEntity<String> refreshResp = restTemplate.postForEntity(
                "/api/v1/auth/refresh",
                new RefreshRequest(originalRefreshToken),
                String.class);

        log.trace("Assert: status={}, tokensRotated=true", refreshResp.getStatusCode());
        assertThat(refreshResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        var refreshBody = objectMapper.readValue(
                refreshResp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        assertThat(refreshBody.success()).isTrue();
        assertThat(refreshBody.data().accessToken()).isNotBlank();
        assertThat(refreshBody.data().refreshToken()).isNotBlank();
        assertThat(refreshBody.data().refreshToken()).isNotEqualTo(originalRefreshToken);
    }

    @Test
    void should_return204_when_logoutWithValidJwt() throws Exception {
        var email = "logout.user@beautica.com";
        var password = "logoutpass1";
        log.debug("Arrange: obtained access token for {}", email);

        restTemplate.postForEntity("/api/v1/auth/register",
                new RegisterRequest(email, password, null, null, null),
                String.class);

        ResponseEntity<String> loginResp = restTemplate.postForEntity(
                "/api/v1/auth/login",
                new LoginRequest(email, password),
                String.class);

        var loginBody = objectMapper.readValue(
                loginResp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        String accessToken = loginBody.data().accessToken();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);

        log.debug("Act: POST /auth/logout with Bearer token");
        ResponseEntity<Void> logoutResp = restTemplate.exchange(
                "/api/v1/auth/logout",
                HttpMethod.POST,
                new HttpEntity<>(headers),
                Void.class);

        log.trace("Assert: status={}", logoutResp.getStatusCode());
        assertThat(logoutResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void should_return401_when_logoutWithoutJwt() {
        log.debug("Arrange: no Authorization header prepared");

        log.debug("Act: POST /auth/logout without credentials");
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/logout", null, String.class);

        log.trace("Assert: status={}", response.getStatusCode());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("should return 401 when old refresh token is replayed after rotation")
    void should_return401_when_oldRefreshTokenReplayedAfterRotation() throws Exception {
        var email = "replay.user@beautica.com";
        var password = "replaypass1";
        log.debug("Arrange: register user email={}", email);

        restTemplate.postForEntity("/api/v1/auth/register",
                new RegisterRequest(email, password, null, null, null),
                String.class);

        ResponseEntity<String> loginResp = restTemplate.postForEntity(
                "/api/v1/auth/login",
                new LoginRequest(email, password),
                String.class);

        var loginBody = objectMapper.readValue(
                loginResp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        String originalRefreshToken = loginBody.data().refreshToken();

        log.debug("Act: first refresh — rotates the token");
        restTemplate.postForEntity(
                "/api/v1/auth/refresh",
                new RefreshRequest(originalRefreshToken),
                String.class);

        log.debug("Act: replay the original refresh token after rotation");
        ResponseEntity<String> replayResp = restTemplate.postForEntity(
                "/api/v1/auth/refresh",
                new RefreshRequest(originalRefreshToken),
                String.class);

        log.trace("Assert: replay is rejected with 401");
        assertThat(replayResp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        var body = objectMapper.readValue(replayResp.getBody(), new TypeReference<ApiResponse<Void>>() {});
        assertThat(body.success()).isFalse();
    }
}
