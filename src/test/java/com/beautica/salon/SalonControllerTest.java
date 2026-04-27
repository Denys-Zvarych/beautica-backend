package com.beautica.salon;

import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.auth.dto.RegisterRequest;
import com.beautica.common.ApiResponse;
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
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.beautica.config.TestSecurityConfig;
import com.beautica.notification.EmailService;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@Import(TestSecurityConfig.class)
@DisplayName("Salon controller — HTTP layer")
class SalonControllerTest {

    private static final Logger log = LoggerFactory.getLogger(SalonControllerTest.class);
    private static final String SALONS_URL = "/api/v1/salons";
    private static final String TEST_PASSWORD = "password123";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

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
    @DisplayName("POST /api/v1/salons — 201 when SALON_OWNER sends valid body")
    void should_return201_when_validSalonCreation() throws Exception {
        String token = createSalonOwnerAndGetToken(
                "owner-create-" + System.nanoTime() + "@beautica.test");

        var request = new CreateSalonRequest("My Salon", null, "Kyiv", null, null, null, null);

        log.debug("Act: POST {} as SALON_OWNER", SALONS_URL);
        ResponseEntity<String> response = restTemplate.exchange(
                SALONS_URL, HttpMethod.POST,
                new HttpEntity<>(request, bearerHeaders(token)),
                String.class);

        log.trace("Assert: status={}", response.getStatusCode());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        var body = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<SalonResponse>>() {});
        assertThat(body.success()).isTrue();
        assertThat(body.data().name()).isEqualTo("My Salon");
    }

    @Test
    @DisplayName("POST /api/v1/salons — 403 when CLIENT token is used")
    void should_return403_when_clientTokenUsedToCreateSalon() throws Exception {
        String token = registerClientAndGetToken(
                "client-create-" + System.nanoTime() + "@beautica.test");

        var request = new CreateSalonRequest("Forbidden Salon", null, null, null, null, null, null);

        log.debug("Act: POST {} as CLIENT", SALONS_URL);
        ResponseEntity<String> response = restTemplate.exchange(
                SALONS_URL, HttpMethod.POST,
                new HttpEntity<>(request, bearerHeaders(token)),
                String.class);

        log.trace("Assert: status={}", response.getStatusCode());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("POST /api/v1/salons — 401 when no Authorization header")
    void should_return401_when_noTokenOnCreateSalon() {
        var request = new CreateSalonRequest("Anon Salon", null, null, null, null, null, null);

        log.debug("Act: POST {} without credentials", SALONS_URL);
        ResponseEntity<String> response = restTemplate.exchange(
                SALONS_URL, HttpMethod.POST,
                new HttpEntity<>(request),
                String.class);

        log.trace("Assert: status={}", response.getStatusCode());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("GET /api/v1/salons/{id} — 200 without authentication (public endpoint)")
    void should_return200_when_publicGetSalon() throws Exception {
        String token = createSalonOwnerAndGetToken(
                "owner-get-" + System.nanoTime() + "@beautica.test");
        UUID salonId = createSalon(token, "Public Salon");

        log.debug("Act: GET {}/{} without credentials", SALONS_URL, salonId);
        ResponseEntity<String> response = restTemplate.getForEntity(
                SALONS_URL + "/" + salonId, String.class);

        log.trace("Assert: status={}", response.getStatusCode());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        var body = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<SalonResponse>>() {});
        assertThat(body.success()).isTrue();
        assertThat(body.data().name()).isEqualTo("Public Salon");
    }

    @Test
    @DisplayName("GET /api/v1/salons/{id} — 404 when salon does not exist")
    void should_return404_when_salonNotFound() {
        UUID unknownId = UUID.randomUUID();

        log.debug("Act: GET {}/{} for non-existent salon", SALONS_URL, unknownId);
        ResponseEntity<String> response = restTemplate.getForEntity(
                SALONS_URL + "/" + unknownId, String.class);

        log.trace("Assert: status={}", response.getStatusCode());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("PATCH /api/v1/salons/{id} — 403 when a different owner patches the salon")
    void should_return403_when_differentOwnerPatchesSalon() throws Exception {
        String ownerToken = createSalonOwnerAndGetToken(
                "owner-patch-" + System.nanoTime() + "@beautica.test");
        UUID salonId = createSalon(ownerToken, "Owner's Salon");

        String attackerToken = createSalonOwnerAndGetToken(
                "attacker-patch-" + System.nanoTime() + "@beautica.test");

        var request = new UpdateSalonRequest("Hijacked", null, null, null, null, null, null);

        log.debug("Act: PATCH {}/{} as a different SALON_OWNER", SALONS_URL, salonId);
        ResponseEntity<String> response = restTemplate.exchange(
                SALONS_URL + "/" + salonId, HttpMethod.PATCH,
                new HttpEntity<>(request, bearerHeaders(attackerToken)),
                String.class);

        log.trace("Assert: status={}", response.getStatusCode());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("POST /api/v1/salons/{id}/invite — 201 when owner sends invite to valid email")
    void should_return201_when_ownerSendsInvite() throws Exception {
        String token = createSalonOwnerAndGetToken(
                "owner-invite-" + System.nanoTime() + "@beautica.test");
        UUID salonId = createSalon(token, "Invite Salon");

        doNothing().when(emailService).sendInviteEmail(anyString(), anyString());
        String inviteEmail = "master-" + System.nanoTime() + "@beautica.test";
        String body = "{\"email\":\"" + inviteEmail + "\"}";

        log.debug("Act: POST {}/{}/invite as SALON_OWNER", SALONS_URL, salonId);
        ResponseEntity<String> response = restTemplate.exchange(
                SALONS_URL + "/" + salonId + "/invite", HttpMethod.POST,
                new HttpEntity<>(body, bearerHeaders(token)),
                String.class);

        log.trace("Assert: status={}", response.getStatusCode());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("POST /api/v1/salons/{id}/invite — 400 when invite email is invalid")
    void should_return400_when_inviteEmailInvalid() throws Exception {
        String token = createSalonOwnerAndGetToken(
                "owner-inv-val-" + System.nanoTime() + "@beautica.test");
        UUID salonId = createSalon(token, "Validation Salon");

        String body = "{\"email\":\"not-an-email\"}";

        log.debug("Act: POST {}/{}/invite with invalid email", SALONS_URL, salonId);
        ResponseEntity<String> response = restTemplate.exchange(
                SALONS_URL + "/" + salonId + "/invite", HttpMethod.POST,
                new HttpEntity<>(body, bearerHeaders(token)),
                String.class);

        log.trace("Assert: status={}", response.getStatusCode());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
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

    private String registerClientAndGetToken(String email) throws Exception {
        var request = new RegisterRequest(email, TEST_PASSWORD, null, null, null);
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/register", request, String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
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
