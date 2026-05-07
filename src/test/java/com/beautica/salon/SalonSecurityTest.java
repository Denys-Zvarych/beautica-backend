package com.beautica.salon;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.beautica.notification.EmailService;
import com.beautica.salon.dto.CreateSalonRequest;
import com.beautica.salon.dto.SalonResponse;
import com.beautica.salon.dto.UpdateSalonRequest;
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
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestSecurityConfig.class)
@DisplayName("Salon — cross-owner security regression")
class SalonSecurityTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(SalonSecurityTest.class);
    private static final String SALONS_URL = "/api/v1/salons";
    private static final String TEST_PASSWORD = "password123";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @org.springframework.boot.test.mock.mockito.MockBean
    private EmailService emailService;

    @BeforeEach
    void configureHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.execute("DELETE FROM invite_tokens");
        jdbcTemplate.execute("DELETE FROM salons");
        jdbcTemplate.execute("DELETE FROM refresh_tokens");
        jdbcTemplate.execute("DELETE FROM users");
    }

    @Test
    @DisplayName("PATCH — 403 when Owner B attempts to patch Owner A's salon")
    void should_return403_when_ownerBPatchesSalonOwnedByOwnerA() throws Exception {
        String ownerAToken = createSalonOwnerAndGetToken(
                "owner-a-patch-" + System.nanoTime() + "@beautica.test");
        UUID salonAId = createSalon(ownerAToken, "Owner A Salon");

        String ownerBToken = createSalonOwnerAndGetToken(
                "owner-b-patch-" + System.nanoTime() + "@beautica.test");

        var patchRequest = new UpdateSalonRequest("Stolen Salon", null, null, null, null, null, null);

        log.debug("Act: PATCH {}/{} with Owner B's token targeting Owner A's salon — cross-owner patch must be denied", SALONS_URL, salonAId);
        ResponseEntity<String> response = restTemplate.exchange(
                SALONS_URL + "/" + salonAId, HttpMethod.PATCH,
                new HttpEntity<>(patchRequest, bearerHeaders(ownerBToken)),
                String.class);

        assertThat(response.getStatusCode())
                .as("status must be 403 when Owner B patches Owner A's salon, salonId=%s", salonAId)
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("POST invite — 403 when Owner B sends invite for Owner A's salon")
    void should_return403_when_ownerBSendsInviteForSalonOwnedByOwnerA() throws Exception {
        String ownerAToken = createSalonOwnerAndGetToken(
                "owner-a-invite-" + System.nanoTime() + "@beautica.test");
        UUID salonAId = createSalon(ownerAToken, "Owner A Salon");

        String ownerBToken = createSalonOwnerAndGetToken(
                "owner-b-invite-" + System.nanoTime() + "@beautica.test");

        String inviteBody = "{\"email\":\"master-" + System.nanoTime() + "@beautica.test\"}";

        log.debug("Act: POST {}/{}/invite with Owner B's token targeting Owner A's salon — cross-owner invite must be denied", SALONS_URL, salonAId);
        ResponseEntity<String> response = restTemplate.exchange(
                SALONS_URL + "/" + salonAId + "/invite", HttpMethod.POST,
                new HttpEntity<>(inviteBody, bearerHeaders(ownerBToken)),
                String.class);

        assertThat(response.getStatusCode())
                .as("status must be 403 when Owner B sends invite for Owner A's salon, salonId=%s", salonAId)
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    private String createSalonOwnerAndGetToken(String email) throws Exception {
        String hash = passwordEncoder.encode(TEST_PASSWORD);
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active) VALUES (?, ?, ?, 'SALON_OWNER', true)",
                UUID.randomUUID(), email, hash);

        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, TEST_PASSWORD), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = objectMapper.readValue(resp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        return body.data().accessToken();
    }

    private UUID createSalon(String ownerToken, String salonName) throws Exception {
        var request = new CreateSalonRequest(salonName, null, null, null, null, null, null);
        ResponseEntity<String> resp = restTemplate.exchange(
                SALONS_URL, HttpMethod.POST,
                new HttpEntity<>(request, bearerHeaders(ownerToken)),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var body = objectMapper.readValue(resp.getBody(), new TypeReference<ApiResponse<SalonResponse>>() {});
        return body.data().id();
    }

    private HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
