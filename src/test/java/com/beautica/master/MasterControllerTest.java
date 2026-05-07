package com.beautica.master;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.Role;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.beautica.master.dto.WorkingHoursRequest;
import com.beautica.notification.EmailService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
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

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestSecurityConfig.class)
@DisplayName("MasterController — HTTP layer")
class MasterControllerTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(MasterControllerTest.class);
    private static final String MASTERS_URL = "/api/v1/masters";
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
        if (!(restTemplate.getRestTemplate().getRequestFactory() instanceof HttpComponentsClientHttpRequestFactory)) {
            restTemplate.getRestTemplate().setRequestFactory(
                    new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
        }
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.execute("DELETE FROM schedule_exceptions");
        jdbcTemplate.execute("DELETE FROM working_hours");
        jdbcTemplate.execute("DELETE FROM master_services");
        jdbcTemplate.execute("DELETE FROM masters");
        jdbcTemplate.execute("DELETE FROM invite_tokens");
        jdbcTemplate.execute("DELETE FROM salons");
        jdbcTemplate.execute("DELETE FROM refresh_tokens");
        jdbcTemplate.execute("DELETE FROM users");
    }

    // ── GET /{masterId} — public ───────────────────────────────────────────────

    @Test
    @DisplayName("GET /{masterId} — 200 without authentication (public endpoint)")
    void should_return200_when_publicGetMasterDetail() {
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
                Role.SALON_MASTER);

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

    // ── GET /{masterId} — 404 on unknown master ────────────────────────────────

    @Test
    @DisplayName("GET /{masterId} — 404 when master does not exist")
    void should_return404_when_getMasterWithUnknownId() {
        UUID unknownMasterId = UUID.randomUUID();

        log.debug("Act: GET {}/{} for a master that does not exist", MASTERS_URL, unknownMasterId);
        ResponseEntity<String> response = restTemplate.getForEntity(
                MASTERS_URL + "/" + unknownMasterId, String.class);

        assertThat(response.getStatusCode())
                .as("status must be 404 when master does not exist, id=%s", unknownMasterId)
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── PATCH /{masterId}/working-hours — 400 on invalid body ─────────────────

    @Test
    @DisplayName("PATCH /{masterId}/working-hours — 400 when body has invalid dayOfWeek (0 fails @Min(1))")
    void should_return400_when_invalidWorkingHoursBodyOnPatchWorkingHours() throws Exception {
        // @PreAuthorize runs before @Valid, so the actor must own the master to reach validation.
        // Create an INDEPENDENT_MASTER user with a matching master record so authz passes.
        String email = "indep-wh-val-" + System.nanoTime() + "@beautica.test";
        String hash = passwordEncoder.encode(TEST_PASSWORD);
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active) VALUES (?, ?, ?, 'INDEPENDENT_MASTER', true)",
                userId, email, hash);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, master_type, is_active, created_at, updated_at) VALUES (?, ?, 'INDEPENDENT_MASTER', true, NOW(), NOW())",
                masterId, userId);

        ResponseEntity<String> loginResp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, TEST_PASSWORD), String.class);
        assertThat(loginResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var loginBody = objectMapper.readValue(loginResp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        String token = loginBody.data().accessToken();

        // dayOfWeek is absent → defaults to 0, which fails @Min(1) constraint
        String body = "[{\"startTime\":\"09:00\",\"endTime\":\"17:00\",\"isActive\":true}]";

        log.debug("Act: PATCH {}/{}/working-hours with invalid body (dayOfWeek=0) — must be rejected with 400",
                MASTERS_URL, masterId);
        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "/" + masterId + "/working-hours", HttpMethod.PATCH,
                new HttpEntity<>(body, bearerHeaders(token)),
                String.class);

        assertThat(response.getStatusCode())
                .as("status must be 400 when working-hours body fails @Min(1) on dayOfWeek")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── POST /{masterId}/schedule-exceptions — 400 on missing date ────────────

    @Test
    @DisplayName("POST /{masterId}/schedule-exceptions — 400 when required 'date' field is absent")
    void should_return400_when_missingDateInScheduleExceptionRequest() throws Exception {
        String email = "indep-exc-" + System.nanoTime() + "@beautica.test";
        String hash = passwordEncoder.encode(TEST_PASSWORD);
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active) VALUES (?, ?, ?, 'INDEPENDENT_MASTER', true)",
                userId, email, hash);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, master_type, is_active, created_at, updated_at) VALUES (?, ?, 'INDEPENDENT_MASTER', true, NOW(), NOW())",
                masterId, userId);

        ResponseEntity<String> loginResp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, TEST_PASSWORD), String.class);
        assertThat(loginResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var loginBody = objectMapper.readValue(loginResp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        String token = loginBody.data().accessToken();

        // date is @NotNull — omitting it must fail Bean Validation with 400
        String body = "{\"reason\":\"HOLIDAY\"}";

        log.debug("Act: POST {}/{}/schedule-exceptions with missing 'date' field — must be rejected with 400",
                MASTERS_URL, masterId);
        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "/" + masterId + "/schedule-exceptions", HttpMethod.POST,
                new HttpEntity<>(body, bearerHeaders(token)),
                String.class);

        assertThat(response.getStatusCode())
                .as("status must be 400 when 'date' (@NotNull) is absent from ScheduleExceptionRequest")
                .isEqualTo(HttpStatus.BAD_REQUEST);
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

    // ── DELETE /{masterId} — 403 when CLIENT attempts to deactivate ───────────

    @Test
    @DisplayName("DELETE /{masterId} — 403 when CLIENT attempts to deactivate a master")
    void should_return403_when_clientAttemptsToDeactivateMaster() throws Exception {
        String clientToken = createUserAndGetToken(
                "client-del-" + System.nanoTime() + "@beautica.test",
                Role.CLIENT);
        UUID randomMasterId = UUID.randomUUID();

        log.debug("Act: DELETE {}/{} as CLIENT — must be denied with 403", MASTERS_URL, randomMasterId);
        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "/" + randomMasterId, HttpMethod.DELETE,
                new HttpEntity<>(bearerHeaders(clientToken)),
                String.class);

        assertThat(response.getStatusCode())
                .as("status must be 403 when CLIENT attempts to deactivate a master")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── DELETE /{masterId}/schedule-exceptions/{date} ─────────────────────────

    @Test
    @DisplayName("DELETE /{masterId}/schedule-exceptions/{date} — 204 when owner removes exception")
    void should_return204_when_ownerRemovesScheduleException() throws Exception {
        String email = "indep-del-exc-" + System.nanoTime() + "@beautica.test";
        String hash = passwordEncoder.encode(TEST_PASSWORD);
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active) VALUES (?, ?, ?, 'INDEPENDENT_MASTER', true)",
                userId, email, hash);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, master_type, is_active, created_at, updated_at) VALUES (?, ?, 'INDEPENDENT_MASTER', true, NOW(), NOW())",
                masterId, userId);
        LocalDate exceptionDate = LocalDate.of(2026, 6, 1);
        jdbcTemplate.update(
                "INSERT INTO schedule_exceptions (id, master_id, date, reason, created_at) VALUES (gen_random_uuid(), ?, ?, 'HOLIDAY', NOW())",
                masterId, exceptionDate);

        ResponseEntity<String> loginResp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, TEST_PASSWORD), String.class);
        assertThat(loginResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var loginBody = objectMapper.readValue(loginResp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        String token = loginBody.data().accessToken();

        log.debug("Act: DELETE {}/{}/schedule-exceptions/{} as INDEPENDENT_MASTER", MASTERS_URL, masterId, exceptionDate);
        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "/" + masterId + "/schedule-exceptions/" + exceptionDate,
                HttpMethod.DELETE,
                new HttpEntity<>(bearerHeaders(token)),
                String.class);

        assertThat(response.getStatusCode())
                .as("status must be 204 when master owner removes their own schedule exception")
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    @DisplayName("DELETE /{masterId}/schedule-exceptions/{date} — 401 when no Authorization header")
    void should_return401_when_noTokenOnDeleteScheduleException() {
        UUID masterId = UUID.randomUUID();
        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "/" + masterId + "/schedule-exceptions/2026-06-01",
                HttpMethod.DELETE,
                new HttpEntity<>(jsonHeaders()),
                String.class);
        assertThat(response.getStatusCode())
                .as("status must be 401 when no token is provided for DELETE schedule-exception")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("DELETE /{masterId}/schedule-exceptions/{date} — 403 when SALON_MASTER calls endpoint")
    void should_return403_when_salonMasterDeletesScheduleException() throws Exception {
        String salonMasterToken = createUserAndGetToken(
                "sm-del-exc-" + System.nanoTime() + "@beautica.test",
                Role.SALON_MASTER);
        UUID masterId = UUID.randomUUID();
        log.debug("Act: DELETE {}/{}/schedule-exceptions/2026-06-01 as SALON_MASTER — must be denied", MASTERS_URL, masterId);
        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "/" + masterId + "/schedule-exceptions/2026-06-01",
                HttpMethod.DELETE,
                new HttpEntity<>(bearerHeaders(salonMasterToken)),
                String.class);
        assertThat(response.getStatusCode())
                .as("status must be 403 when SALON_MASTER attempts to delete a schedule exception")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── PATCH /{masterId}/working-hours — 400 startTime after endTime ─────────

    @Test
    @DisplayName("PATCH /{masterId}/working-hours — 400 when startTime is after endTime")
    void should_return400_when_workingHoursStartTimeIsAfterEndTime() throws Exception {
        String email = "indep-time-" + System.nanoTime() + "@beautica.test";
        String hash = passwordEncoder.encode(TEST_PASSWORD);
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active) VALUES (?, ?, ?, 'INDEPENDENT_MASTER', true)",
                userId, email, hash);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, master_type, is_active, created_at, updated_at) VALUES (?, ?, 'INDEPENDENT_MASTER', true, NOW(), NOW())",
                masterId, userId);

        ResponseEntity<String> loginResp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, TEST_PASSWORD), String.class);
        assertThat(loginResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var loginBody = objectMapper.readValue(loginResp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        String token = loginBody.data().accessToken();

        // startTime 17:00 > endTime 09:00 — must fail @AssertTrue isTimeRangeValid
        String body = "[{\"dayOfWeek\":1,\"startTime\":\"17:00\",\"endTime\":\"09:00\",\"isActive\":true}]";
        log.debug("Act: PATCH {}/{}/working-hours with startTime after endTime", MASTERS_URL, masterId);
        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "/" + masterId + "/working-hours", HttpMethod.PATCH,
                new HttpEntity<>(body, bearerHeaders(token)),
                String.class);

        assertThat(response.getStatusCode())
                .as("status must be 400 when working-hours startTime is after endTime")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── POST /{masterId}/schedule-exceptions — 400 when date is in the past ───

    @Test
    @DisplayName("POST /{masterId}/schedule-exceptions — 400 when date is in the past")
    void should_return400_when_scheduleExceptionDateIsInThePast() throws Exception {
        String email = "indep-past-" + System.nanoTime() + "@beautica.test";
        String hash = passwordEncoder.encode(TEST_PASSWORD);
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active) VALUES (?, ?, ?, 'INDEPENDENT_MASTER', true)",
                userId, email, hash);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, master_type, is_active, created_at, updated_at) VALUES (?, ?, 'INDEPENDENT_MASTER', true, NOW(), NOW())",
                masterId, userId);

        ResponseEntity<String> loginResp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, TEST_PASSWORD), String.class);
        assertThat(loginResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var loginBody = objectMapper.readValue(loginResp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        String token = loginBody.data().accessToken();

        // date in 2020 — clearly in the past, fails @FutureOrPresent
        String body = "{\"date\":\"2020-01-01\",\"reason\":\"HOLIDAY\"}";
        log.debug("Act: POST {}/{}/schedule-exceptions with past date 2020-01-01", MASTERS_URL, masterId);
        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "/" + masterId + "/schedule-exceptions", HttpMethod.POST,
                new HttpEntity<>(body, bearerHeaders(token)),
                String.class);

        assertThat(response.getStatusCode())
                .as("status must be 400 when schedule exception date is in the past")
                .isEqualTo(HttpStatus.BAD_REQUEST);
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

    private String createUserAndGetToken(String email, Role role) throws Exception {
        String hash = passwordEncoder.encode(TEST_PASSWORD);
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active) VALUES (?, ?, ?, ?, true)",
                UUID.randomUUID(), email, hash, role.name());

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
