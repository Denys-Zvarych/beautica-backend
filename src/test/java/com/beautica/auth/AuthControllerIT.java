package com.beautica.auth;

import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.auth.dto.RefreshRequest;
import com.beautica.auth.dto.RegisterRequest;
import com.beautica.common.ApiResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
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
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class AuthControllerIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void should_return201_when_registerWithValidData() {
        var request = new RegisterRequest(
                "register@beautica.com", "password123",
                "Anna", "Test", null);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/auth/register", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void should_return400_when_registerWithDuplicateEmail() {
        var request = new RegisterRequest(
                "duplicate@beautica.com", "password123",
                null, null, null);

        restTemplate.postForEntity("/auth/register", request, String.class);

        ResponseEntity<String> secondResponse = restTemplate.postForEntity(
                "/auth/register", request, String.class);

        assertThat(secondResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void should_return200WithTokenPair_when_loginWithValidCredentials() throws Exception {
        var email = "login.valid@beautica.com";
        var password = "mypassword1";

        restTemplate.postForEntity("/auth/register",
                new RegisterRequest(email, password, null, null, null),
                String.class);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/auth/login",
                new LoginRequest(email, password),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        var apiResponse = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        assertThat(apiResponse.success()).isTrue();
        assertThat(apiResponse.data().accessToken()).isNotBlank();
        assertThat(apiResponse.data().refreshToken()).isNotBlank();
        assertThat(apiResponse.data().tokenType()).isEqualTo("Bearer");
    }

    @Test
    void should_return400_when_loginWithBadCredentials() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/auth/login",
                new LoginRequest("nonexistent@beautica.com", "wrongpass"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void should_return200WithNewTokenPair_when_refreshWithValidToken() throws Exception {
        var email = "refresh.user@beautica.com";
        var password = "refreshpass1";

        restTemplate.postForEntity("/auth/register",
                new RegisterRequest(email, password, null, null, null),
                String.class);

        ResponseEntity<String> loginResp = restTemplate.postForEntity(
                "/auth/login",
                new LoginRequest(email, password),
                String.class);

        var loginBody = objectMapper.readValue(
                loginResp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        String refreshToken = loginBody.data().refreshToken();

        ResponseEntity<String> refreshResp = restTemplate.postForEntity(
                "/auth/refresh",
                new RefreshRequest(refreshToken),
                String.class);

        assertThat(refreshResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        var refreshBody = objectMapper.readValue(
                refreshResp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        assertThat(refreshBody.data().accessToken()).isNotBlank();
    }

    @Test
    void should_return204_when_logoutWithValidJwt() throws Exception {
        var email = "logout.user@beautica.com";
        var password = "logoutpass1";

        restTemplate.postForEntity("/auth/register",
                new RegisterRequest(email, password, null, null, null),
                String.class);

        ResponseEntity<String> loginResp = restTemplate.postForEntity(
                "/auth/login",
                new LoginRequest(email, password),
                String.class);

        var loginBody = objectMapper.readValue(
                loginResp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        String accessToken = loginBody.data().accessToken();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Void> logoutResp = restTemplate.exchange(
                "/auth/logout",
                HttpMethod.POST,
                new HttpEntity<>(headers),
                Void.class);

        assertThat(logoutResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void should_return401_when_logoutWithoutJwt() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/auth/logout", null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
