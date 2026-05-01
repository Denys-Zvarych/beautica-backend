package com.beautica.auth;

import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.auth.dto.RefreshRequest;
import com.beautica.auth.dto.RegisterRequest;
import com.beautica.auth.dto.SelfRegistrationRole;
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
    @DisplayName("should return 201 with token pair and user metadata when registering with valid data")
    void should_return201_when_registerWithValidData() throws Exception {
        var request = new RegisterRequest(
                "register@beautica.com", "password123",
                SelfRegistrationRole.CLIENT, "Anna", "Test", null, null);
        log.debug("Arrange: register request for email={}", request.email());

        log.debug("Act: POST /auth/register with valid CLIENT payload");
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/register", request, String.class);

        assertThat(response.getStatusCode())
                .as("status for valid CLIENT registration, email=%s", request.email())
                .isEqualTo(HttpStatus.CREATED);

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
    @DisplayName("should return 409 when registering with an email that is already taken")
    void should_return409_when_registerWithDuplicateEmail() throws Exception {
        log.debug("Arrange: registering email={} twice", "duplicate@beautica.com");
        var request = new RegisterRequest(
                "duplicate@beautica.com", "password123",
                SelfRegistrationRole.CLIENT, null, null, null, null);

        restTemplate.postForEntity("/api/v1/auth/register", request, String.class);

        log.debug("Act: POST /auth/register with already-registered duplicate@beautica.com");
        ResponseEntity<String> secondResponse = restTemplate.postForEntity(
                "/api/v1/auth/register", request, String.class);

        assertThat(secondResponse.getStatusCode())
                .as("status when same email is registered twice")
                .isEqualTo(HttpStatus.CONFLICT);

        var apiResponse = objectMapper.readValue(
                secondResponse.getBody(), new TypeReference<ApiResponse<Void>>() {});
        assertThat(apiResponse.success()).isFalse();
        assertThat(apiResponse.message()).isNotBlank();
    }

    @Test
    @DisplayName("should return 200 with access and refresh tokens when login credentials are valid")
    void should_return200WithTokenPair_when_loginWithValidCredentials() throws Exception {
        var email = "login.valid@beautica.com";
        var password = "mypassword1";
        log.debug("Arrange: pre-registering email={} with known password", email);

        restTemplate.postForEntity("/api/v1/auth/register",
                new RegisterRequest(email, password, SelfRegistrationRole.CLIENT, null, null, null, null),
                String.class);

        log.debug("Act: POST /auth/login with correct credentials for email={}", email);
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/login",
                new LoginRequest(email, password),
                String.class);

        assertThat(response.getStatusCode())
                .as("status for login with correct credentials, email=%s", email)
                .isEqualTo(HttpStatus.OK);

        var apiResponse = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        assertThat(apiResponse.success()).isTrue();
        assertThat(apiResponse.data().accessToken()).isNotBlank();
        assertThat(apiResponse.data().refreshToken()).isNotBlank();
        assertThat(apiResponse.data().tokenType()).isEqualTo("Bearer");
        assertThat(apiResponse.data().email()).isEqualTo(email);
    }

    @Test
    @DisplayName("should return 401 when login is attempted with a non-existent email and wrong password")
    void should_return401_when_loginWithBadCredentials() throws Exception {
        log.debug("Arrange: no pre-existing user");

        log.debug("Act: POST /auth/login with unknown email nonexistent@beautica.com");
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/login",
                new LoginRequest("nonexistent@beautica.com", "wrongpass"),
                String.class);

        assertThat(response.getStatusCode())
                .as("status for login with unknown email")
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        var apiResponse = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<Void>>() {});
        assertThat(apiResponse.success()).isFalse();
        assertThat(apiResponse.message()).isNotBlank();
    }

    @Test
    @DisplayName("should return 200 with a new rotated token pair when refresh token is valid")
    void should_return200WithNewTokenPair_when_refreshWithValidToken() throws Exception {
        var email = "refresh.user@beautica.com";
        var password = "refreshpass1";
        log.debug("Arrange: user {} registered and logged in", email);

        restTemplate.postForEntity("/api/v1/auth/register",
                new RegisterRequest(email, password, SelfRegistrationRole.CLIENT, null, null, null, null),
                String.class);

        ResponseEntity<String> loginResp = restTemplate.postForEntity(
                "/api/v1/auth/login",
                new LoginRequest(email, password),
                String.class);

        var loginBody = objectMapper.readValue(
                loginResp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        String originalRefreshToken = loginBody.data().refreshToken();

        log.debug("Act: POST /auth/refresh using the original refresh token for email={}", email);
        ResponseEntity<String> refreshResp = restTemplate.postForEntity(
                "/api/v1/auth/refresh",
                new RefreshRequest(originalRefreshToken),
                String.class);

        assertThat(refreshResp.getStatusCode())
                .as("status for valid token refresh, email=%s", email)
                .isEqualTo(HttpStatus.OK);

        var refreshBody = objectMapper.readValue(
                refreshResp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        assertThat(refreshBody.success()).isTrue();
        assertThat(refreshBody.data().accessToken()).isNotBlank();
        assertThat(refreshBody.data().refreshToken()).isNotBlank();
        assertThat(refreshBody.data().refreshToken()).isNotEqualTo(originalRefreshToken);
    }

    @Test
    @DisplayName("should return 204 when logout is called with a valid Bearer JWT")
    void should_return204_when_logoutWithValidJwt() throws Exception {
        var email = "logout.user@beautica.com";
        var password = "logoutpass1";
        log.debug("Arrange: obtained access token for {}", email);

        restTemplate.postForEntity("/api/v1/auth/register",
                new RegisterRequest(email, password, SelfRegistrationRole.CLIENT, null, null, null, null),
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

        log.debug("Act: POST /auth/logout with valid Bearer token for email={}", email);
        ResponseEntity<Void> logoutResp = restTemplate.exchange(
                "/api/v1/auth/logout",
                HttpMethod.POST,
                new HttpEntity<>(headers),
                Void.class);

        assertThat(logoutResp.getStatusCode())
                .as("status for logout with valid Bearer token")
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    @DisplayName("should return 401 when logout is called without an Authorization header")
    void should_return401_when_logoutWithoutJwt() {
        log.debug("Arrange: no Authorization header prepared");

        log.debug("Act: POST /auth/logout with no Authorization header");
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/logout", null, String.class);

        assertThat(response.getStatusCode())
                .as("status for logout without Authorization header")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("should return 401 when old refresh token is replayed after rotation")
    void should_return401_when_oldRefreshTokenReplayedAfterRotation() throws Exception {
        var email = "replay.user@beautica.com";
        var password = "replaypass1";
        log.debug("Arrange: register user email={}", email);

        restTemplate.postForEntity("/api/v1/auth/register",
                new RegisterRequest(email, password, SelfRegistrationRole.CLIENT, null, null, null, null),
                String.class);

        ResponseEntity<String> loginResp = restTemplate.postForEntity(
                "/api/v1/auth/login",
                new LoginRequest(email, password),
                String.class);

        var loginBody = objectMapper.readValue(
                loginResp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        String originalRefreshToken = loginBody.data().refreshToken();

        log.debug("Act: first refresh — rotates the token for email={}", email);
        restTemplate.postForEntity(
                "/api/v1/auth/refresh",
                new RefreshRequest(originalRefreshToken),
                String.class);

        log.debug("Act: replay the original (now-rotated) refresh token for email={}", email);
        ResponseEntity<String> replayResp = restTemplate.postForEntity(
                "/api/v1/auth/refresh",
                new RefreshRequest(originalRefreshToken),
                String.class);

        assertThat(replayResp.getStatusCode())
                .as("status when replaying a rotated-out refresh token")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        var body = objectMapper.readValue(replayResp.getBody(), new TypeReference<ApiResponse<Void>>() {});
        assertThat(body.success())
                .as("success flag must be false for revoked token replay")
                .isFalse();
    }
}
