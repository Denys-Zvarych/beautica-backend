package com.beautica.master;

import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.common.ApiResponse;
import com.beautica.master.dto.WorkingHoursRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@Import(TestSecurityConfig.class)
@DisplayName("MasterController — HTTP layer")
class MasterControllerTest {

    private static final Logger log = LoggerFactory.getLogger(MasterControllerTest.class);
    private static final String MASTERS_URL = "/api/v1/masters";
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
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.execute("DELETE FROM schedule_exceptions");
        jdbcTemplate.execute("DELETE FROM working_hours");
        jdbcTemplate.execute("DELETE FROM masters");
        jdbcTemplate.execute("DELETE FROM invite_tokens");
        jdbcTemplate.execute("DELETE FROM salons");
        jdbcTemplate.execute("DELETE FROM refresh_tokens");
        jdbcTemplate.execute("DELETE FROM users");
    }

    // ── GET /{masterId} — public ───────────────────────────────────────────────

    @Test
    @DisplayName("GET /{masterId} — 200 without authentication (public endpoint)")
    void should_return200_when_publicGetMasterDetail() throws Exception {
        String ownerToken = createSalonOwnerAndGetToken(
                "owner-get-master-" + System.nanoTime() + "@beautica.test");
        UUID salonId = createSalon(ownerToken, "Test Salon");
        UUID masterId = createIndependentMaster("indep-" + System.nanoTime() + "@beautica.test");

        log.debug("Act: GET {}/{} without credentials — public endpoint", MASTERS_URL, masterId);
        ResponseEntity<String> response = restTemplate.getForEntity(
                MASTERS_URL + "/" + masterId, String.class);

        assertThat(response.getStatusCode())
                .as("status must be 200 for public GET master detail, masterId=%s", masterId)
                .isEqualTo(HttpStatus.OK);
    }

    // ── PATCH /{masterId}/working-hours — protected ────────────────────────────

    @Test
    @DisplayName("PATCH /{masterId}/working-hours — 403 when SALON_MASTER role is used")
    void should_return403_when_unauthorizedActorPatchesWorkingHours() throws Exception {
        UUID masterId = UUID.randomUUID();

        String salonMasterToken = createUserAndGetToken(
                "salon-master-" + System.nanoTime() + "@beautica.test",
                "SALON_MASTER");

        var requests = List.of(
                new WorkingHoursRequest(1, LocalTime.of(9, 0), LocalTime.of(17, 0), true));
        String body = objectMapper.writeValueAsString(requests);

        log.debug("Act: PATCH {}/{}/working-hours as SALON_MASTER — must be denied", MASTERS_URL, masterId);
        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "/" + masterId + "/working-hours", HttpMethod.PATCH,
                new HttpEntity<>(body, bearerHeaders(salonMasterToken)),
                String.class);

        assertThat(response.getStatusCode())
                .as("status must be 403 when SALON_MASTER attempts to update working hours")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("PATCH /{masterId}/working-hours — 401 when no Authorization header")
    void should_return401_when_noTokenOnPatchWorkingHours() throws Exception {
        UUID masterId = UUID.randomUUID();

        var requests = List.of(
                new WorkingHoursRequest(1, LocalTime.of(9, 0), LocalTime.of(17, 0), true));
        String body = objectMapper.writeValueAsString(requests);

        log.debug("Act: PATCH {}/{}/working-hours without credentials", MASTERS_URL, masterId);
        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "/" + masterId + "/working-hours", HttpMethod.PATCH,
                new HttpEntity<>(body, jsonHeaders()),
                String.class);

        assertThat(response.getStatusCode())
                .as("status must be 401 when no Authorization header is sent to PATCH working hours")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── DELETE /{masterId} — deactivate ───────────────────────────────────────

    @Test
    @DisplayName("DELETE /{masterId} — 204 when salon owner deactivates their master")
    void should_return204_when_authorizedActorDeactivatesMaster() throws Exception {
        String ownerToken = createSalonOwnerAndGetToken(
                "owner-del-" + System.nanoTime() + "@beautica.test");
        UUID salonId = createSalon(ownerToken, "Del Salon");
        UUID masterId = createSalonMaster(
                "master-del-" + System.nanoTime() + "@beautica.test", salonId);

        log.debug("Act: DELETE {}/{} as SALON_OWNER", MASTERS_URL, masterId);
        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "/" + masterId, HttpMethod.DELETE,
                new HttpEntity<>(bearerHeaders(ownerToken)),
                String.class);

        assertThat(response.getStatusCode())
                .as("status must be 204 when salon owner deactivates their master, masterId=%s", masterId)
                .isEqualTo(HttpStatus.NO_CONTENT);

        Boolean isActive = jdbcTemplate.queryForObject(
                "SELECT is_active FROM masters WHERE id = ?", Boolean.class, masterId);
        assertThat(isActive).isFalse();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

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

    private String createUserAndGetToken(String email, String role) throws Exception {
        String hash = passwordEncoder.encode(TEST_PASSWORD);
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active) VALUES (?, ?, ?, ?, true)",
                UUID.randomUUID(), email, hash, role);

        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, TEST_PASSWORD), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = objectMapper.readValue(resp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        return body.data().accessToken();
    }

    private UUID createSalon(String ownerToken, String name) throws Exception {
        String body = "{\"name\":\"" + name + "\",\"city\":\"Kyiv\"}";
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/salons", HttpMethod.POST,
                new HttpEntity<>(body, bearerHeaders(ownerToken)),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var apiResp = objectMapper.readValue(resp.getBody(),
                new TypeReference<ApiResponse<java.util.Map<String, Object>>>() {});
        return UUID.fromString((String) apiResp.data().get("id"));
    }

    private UUID createIndependentMaster(String email) {
        String hash = passwordEncoder.encode(TEST_PASSWORD);
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active) VALUES (?, ?, ?, 'INDEPENDENT_MASTER', true)",
                userId, email, hash);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, master_type, is_active, created_at, updated_at) VALUES (?, ?, 'INDEPENDENT_MASTER', true, NOW(), NOW())",
                masterId, userId);
        return masterId;
    }

    private UUID createSalonMaster(String email, UUID salonId) {
        String hash = passwordEncoder.encode(TEST_PASSWORD);
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active) VALUES (?, ?, ?, 'SALON_MASTER', true)",
                userId, email, hash);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) VALUES (?, ?, ?, 'SALON_MASTER', true, NOW(), NOW())",
                masterId, userId, salonId);
        return masterId;
    }

    private HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
