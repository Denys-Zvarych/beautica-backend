package com.beautica.salon;

import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.auth.dto.RegisterRequest;
import com.beautica.auth.dto.SelfRegistrationRole;
import com.beautica.common.ApiResponse;
import com.beautica.salon.dto.CreateSalonRequest;
import com.beautica.salon.dto.SalonResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.beautica.config.TestSecurityConfig;
import com.beautica.notification.EmailService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@Import(TestSecurityConfig.class)
@DisplayName("GET /api/v1/salons/mine — multi-salon controller")
class SalonControllerMultiTest {

    private static final String SALONS_URL = "/api/v1/salons";
    private static final String MINE_URL = "/api/v1/salons/mine";
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

    @AfterEach
    void cleanUp() {
        jdbcTemplate.execute("DELETE FROM invite_tokens");
        jdbcTemplate.execute("DELETE FROM masters");
        jdbcTemplate.execute("DELETE FROM salons");
        jdbcTemplate.execute("DELETE FROM refresh_tokens");
        jdbcTemplate.execute("DELETE FROM users");
    }

    // -------------------------------------------------------------------------
    // 200 — SALON_OWNER with multiple salons gets all of them
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("GET /mine — 200 with all salons when SALON_OWNER has multiple")
    void should_return200WithAllSalons_when_ownerHasMultiple() throws Exception {
        String token = createSalonOwnerAndGetToken("owner-mine-multi-" + System.nanoTime() + "@beautica.test");

        createSalon(token, "Alpha Salon");
        createSalon(token, "Beta Salon");
        createSalon(token, "Gamma Salon");

        ResponseEntity<String> response = restTemplate.exchange(
                MINE_URL, HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(token)),
                String.class);

        assertThat(response.getStatusCode())
                .as("status must be 200 when SALON_OWNER requests their own salons")
                .isEqualTo(HttpStatus.OK);

        var body = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<List<SalonResponse>>>() {});
        assertThat(body.success()).isTrue();
        assertThat(body.data())
                .as("all three salons must be returned")
                .hasSize(3);
        assertThat(body.data()).extracting(SalonResponse::name)
                .containsExactlyInAnyOrder("Alpha Salon", "Beta Salon", "Gamma Salon");
    }

    // -------------------------------------------------------------------------
    // 200 — SALON_OWNER with no salons gets an empty list
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("GET /mine — 200 with empty list when SALON_OWNER has no salons")
    void should_return200WithEmptyList_when_ownerHasNoSalons() throws Exception {
        String token = createSalonOwnerAndGetToken("owner-mine-empty-" + System.nanoTime() + "@beautica.test");

        ResponseEntity<String> response = restTemplate.exchange(
                MINE_URL, HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(token)),
                String.class);

        assertThat(response.getStatusCode())
                .as("status must be 200 when SALON_OWNER has no salons")
                .isEqualTo(HttpStatus.OK);

        var body = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<List<SalonResponse>>>() {});
        assertThat(body.success()).isTrue();
        assertThat(body.data())
                .as("data must be an empty list when the owner owns no salons")
                .isEmpty();
    }

    // -------------------------------------------------------------------------
    // 403 — caller without SALON_OWNER role is denied
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("GET /mine — 403 when caller is not SALON_OWNER")
    void should_return403_when_callerIsNotSalonOwner() throws Exception {
        String clientToken = registerClientAndGetToken("client-mine-" + System.nanoTime() + "@beautica.test");

        ResponseEntity<String> response = restTemplate.exchange(
                MINE_URL, HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(clientToken)),
                String.class);

        assertThat(response.getStatusCode())
                .as("status must be 403 when a CLIENT attempts to call GET /mine")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // -------------------------------------------------------------------------
    // 401 — unauthenticated request is rejected before reaching the controller
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("GET /mine — no token → 401")
    void should_return401_when_noTokenProvided() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                MINE_URL, String.class);

        assertThat(response.getStatusCode())
                .as("status must be 401 when no Authorization header is sent")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // -------------------------------------------------------------------------
    // Data isolation — each owner sees only their own salons
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("GET /mine — each owner sees only their own salons")
    void should_returnOnlyOwnSalons_when_multipleOwnersExist() throws Exception {
        String tokenA = createSalonOwnerAndGetToken("owner-a-" + System.nanoTime() + "@beautica.test");
        String tokenB = createSalonOwnerAndGetToken("owner-b-" + System.nanoTime() + "@beautica.test");

        createSalon(tokenA, "Salon of Owner A");
        createSalon(tokenB, "Salon of Owner B");

        ResponseEntity<String> responseA = restTemplate.exchange(
                MINE_URL, HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(tokenA)),
                String.class);
        ResponseEntity<String> responseB = restTemplate.exchange(
                MINE_URL, HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(tokenB)),
                String.class);

        assertThat(responseA.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(responseB.getStatusCode()).isEqualTo(HttpStatus.OK);

        var bodyA = objectMapper.readValue(
                responseA.getBody(), new TypeReference<ApiResponse<List<SalonResponse>>>() {});
        var bodyB = objectMapper.readValue(
                responseB.getBody(), new TypeReference<ApiResponse<List<SalonResponse>>>() {});

        assertThat(bodyA.data())
                .as("ownerA must see only their own salon")
                .hasSize(1)
                .extracting(SalonResponse::name)
                .containsExactly("Salon of Owner A");

        assertThat(bodyB.data())
                .as("ownerB must see only their own salon")
                .hasSize(1)
                .extracting(SalonResponse::name)
                .containsExactly("Salon of Owner B");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

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
        var request = new RegisterRequest(email, TEST_PASSWORD, SelfRegistrationRole.CLIENT, null, null, null, null);
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/register", request, String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var body = objectMapper.readValue(resp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        return body.data().accessToken();
    }

    private void createSalon(String ownerToken, String salonName) throws Exception {
        var request = new CreateSalonRequest(salonName, null, null, null, null, null, null);
        ResponseEntity<String> resp = restTemplate.exchange(
                SALONS_URL, HttpMethod.POST,
                new HttpEntity<>(request, bearerHeaders(ownerToken)),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
