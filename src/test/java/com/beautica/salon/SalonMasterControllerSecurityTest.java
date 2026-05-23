package com.beautica.salon;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.booking.dto.BookingResponse;
import com.beautica.booking.dto.CreateBookingRequest;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.beautica.master.dto.MasterDetailResponse;
import com.beautica.notification.service.NotificationOutboxService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 12.7 — negative-only integration tests pinning the authorization boundary on the
 * owner-as-master enable/disable surface.
 *
 * <p>Tests use real HTTP through {@link TestRestTemplate} backed by a Testcontainers PostgreSQL
 * instance. All data is inserted via direct JDBC to keep setup fast and to avoid exercising
 * registration flows that are irrelevant to the security contract.
 *
 * <p>Cleanup is handled entirely by {@link AbstractIntegrationTest#cleanDb()} which runs
 * {@code @AfterEach} in the correct FK order.
 */
@Import(TestSecurityConfig.class)
@DisplayName("SalonMasterController — authorization boundary (Phase 12.7)")
class SalonMasterControllerSecurityTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(SalonMasterControllerSecurityTest.class);

    private static final String SALON_MASTER_URL = "/api/v1/salons/%s/master";
    private static final String MASTERS_SLOTS_URL = "/api/v1/masters/%s/slots";
    private static final String BOOKINGS_URL = "/api/v1/bookings";
    private static final String BOOKING_BY_ID_URL = "/api/v1/bookings/%s";
    private static final String TEST_PASSWORD = "Str0ngP@ss1!";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockBean
    private NotificationOutboxService notificationOutboxService;

    @BeforeEach
    void configureHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
    }

    // ── Test 1: SALON_ADMIN → POST enable → 403 ──────────────────────────────

    @Test
    @DisplayName("POST /{salonId}/master — 403 when SALON_ADMIN calls enable (role guard blocks before authz)")
    void should_return403_when_salonAdminCallsEnableOwnerMaster() throws Exception {
        // Arrange
        UUID ownerId = insertUser("admin-enable-owner-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonId = insertSalon(ownerId, "Admin Enable Test Salon");
        UUID adminId = insertSalonAdminUser("admin-enable-admin-" + System.nanoTime() + "@beautica.test", salonId);
        String adminToken = loginAndGetToken(
                jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, adminId));

        // Act
        log.debug("Act: POST {} as SALON_ADMIN — role guard must deny with 403", String.format(SALON_MASTER_URL, salonId));
        ResponseEntity<String> response = restTemplate.exchange(
                String.format(SALON_MASTER_URL, salonId), HttpMethod.POST,
                new HttpEntity<>(bearerHeaders(adminToken)),
                String.class);

        // Assert
        assertThat(response.getStatusCode())
                .as("SALON_ADMIN must be denied access to enable owner-master, salonId=%s", salonId)
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── Test 2: SALON_MASTER → POST enable → 403 ─────────────────────────────

    @Test
    @DisplayName("POST /{salonId}/master — 403 when SALON_MASTER calls enable (role guard blocks before authz)")
    void should_return403_when_salonMasterCallsEnableOwnerMaster() throws Exception {
        // Arrange
        UUID ownerId = insertUser("sm-enable-owner-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonId = insertSalon(ownerId, "SalonMaster Enable Test Salon");
        UUID salonMasterUserId = insertSalonMasterUser(
                "sm-enable-master-" + System.nanoTime() + "@beautica.test", salonId);
        String masterToken = loginAndGetToken(
                jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, salonMasterUserId));

        // Act
        log.debug("Act: POST {} as SALON_MASTER — role guard must deny with 403", String.format(SALON_MASTER_URL, salonId));
        ResponseEntity<String> response = restTemplate.exchange(
                String.format(SALON_MASTER_URL, salonId), HttpMethod.POST,
                new HttpEntity<>(bearerHeaders(masterToken)),
                String.class);

        // Assert
        assertThat(response.getStatusCode())
                .as("SALON_MASTER must be denied access to enable owner-master, salonId=%s", salonId)
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── Test 3: CLIENT → POST enable → 403 ───────────────────────────────────

    @Test
    @DisplayName("POST /{salonId}/master — 403 when CLIENT calls enable")
    void should_return403_when_clientCallsEnableOwnerMaster() throws Exception {
        // Arrange
        UUID ownerId = insertUser("client-enable-owner-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonId = insertSalon(ownerId, "Client Enable Test Salon");
        UUID clientId = insertUser("client-enable-client-" + System.nanoTime() + "@beautica.test", "CLIENT");
        String clientToken = loginAndGetToken(
                jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, clientId));

        // Act
        log.debug("Act: POST {} as CLIENT — role guard must deny with 403", String.format(SALON_MASTER_URL, salonId));
        ResponseEntity<String> response = restTemplate.exchange(
                String.format(SALON_MASTER_URL, salonId), HttpMethod.POST,
                new HttpEntity<>(bearerHeaders(clientToken)),
                String.class);

        // Assert
        assertThat(response.getStatusCode())
                .as("CLIENT must be denied access to enable owner-master, salonId=%s", salonId)
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── Test 4: Owner B on Salon A → POST enable → 403 (IDOR pin) ────────────

    @Test
    @DisplayName("POST /{salonId}/master — 403 when SALON_OWNER of salon B targets salon A (IDOR boundary)")
    void should_return403_when_ownerBEnablesMasterInSalonOwnedByOwnerA() throws Exception {
        // Arrange — salon A owned by owner A
        UUID ownerAId = insertUser("idor-owner-a-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonAId = insertSalon(ownerAId, "Salon A");

        // Arrange — owner B has their own salon, does NOT own salon A
        UUID ownerBId = insertUser("idor-owner-b-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        insertSalon(ownerBId, "Salon B");
        String ownerBToken = loginAndGetToken(
                jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, ownerBId));

        // Act — owner B targets salon A
        log.debug("Act: POST {} with Owner B token targeting salon A — canManageSalon must block with 403",
                String.format(SALON_MASTER_URL, salonAId));
        ResponseEntity<String> response = restTemplate.exchange(
                String.format(SALON_MASTER_URL, salonAId), HttpMethod.POST,
                new HttpEntity<>(bearerHeaders(ownerBToken)),
                String.class);

        // Assert
        assertThat(response.getStatusCode())
                .as("Owner B must not be able to enable owner-master in salon A (IDOR), salonAId=%s", salonAId)
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── Test 5: After enable→disable, GET /{masterId}/slots returns empty list ─

    @Test
    @DisplayName("GET /{masterId}/slots — empty list (not 404) after owner-master is disabled")
    void should_returnEmptySlots_when_ownerMasterIsDisabled() throws Exception {
        // Arrange — create owner, salon, enable owner-master via API, assign service, add hours
        UUID ownerId = insertUser("slots-owner-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonId = insertSalon(ownerId, "Slots Disabled Test Salon");
        String ownerToken = loginAndGetToken(
                jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, ownerId));

        // Enable the owner-master via the Phase 12.4 endpoint
        ResponseEntity<String> enableResp = restTemplate.exchange(
                String.format(SALON_MASTER_URL, salonId), HttpMethod.POST,
                new HttpEntity<>(bearerHeaders(ownerToken)),
                String.class);
        assertThat(enableResp.getStatusCode())
                .as("enable owner-master must return 200")
                .isEqualTo(HttpStatus.OK);
        var enableBody = objectMapper.readValue(enableResp.getBody(),
                new TypeReference<ApiResponse<MasterDetailResponse>>() {});
        UUID masterId = enableBody.data().masterId();

        // Insert a service definition and working hours via JDBC (bypassing full booking flow)
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, base_duration_minutes, base_price, buffer_minutes_after, is_active, created_at, updated_at) VALUES (?, 'SALON', ?, 'Haircut', 60, 500.00, 0, true, NOW(), NOW())",
                serviceDefId, salonId);
        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId);
        for (int day = 1; day <= 7; day++) {
            jdbcTemplate.update(
                    "INSERT INTO working_hours (id, master_id, day_of_week, start_time, end_time, is_active) VALUES (?, ?, ?, '08:00', '20:00', true)",
                    UUID.randomUUID(), masterId, day);
        }

        // Disable the owner-master via the Phase 12.4 endpoint
        ResponseEntity<Void> disableResp = restTemplate.exchange(
                String.format(SALON_MASTER_URL, salonId), HttpMethod.DELETE,
                new HttpEntity<>(bearerHeaders(ownerToken)),
                Void.class);
        assertThat(disableResp.getStatusCode())
                .as("disable owner-master must return 204")
                .isEqualTo(HttpStatus.NO_CONTENT);

        // Act — fetch slots for tomorrow; master is inactive so no slots should be bookable
        // An authenticated caller is needed because GET /{masterId}/slots requires isAuthenticated()
        UUID clientId = insertUser("slots-client-" + System.nanoTime() + "@beautica.test", "CLIENT");
        String clientToken = loginAndGetToken(
                jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, clientId));

        LocalDate tomorrow = LocalDate.now().plusDays(1);
        String slotsUrl = String.format(MASTERS_SLOTS_URL, masterId)
                + "?date=" + tomorrow + "&serviceId=" + masterServiceId;

        log.debug("Act: GET {} — master is inactive so slot list must be empty", slotsUrl);
        ResponseEntity<String> slotsResp = restTemplate.exchange(
                slotsUrl, HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(clientToken)),
                String.class);

        // Assert — endpoint returns 200 (master record still exists) with an empty slots array
        assertThat(slotsResp.getStatusCode())
                .as("GET slots for a disabled master must return 200 (master row still exists), masterId=%s", masterId)
                .isEqualTo(HttpStatus.OK);

        var slotsNode = objectMapper.readTree(slotsResp.getBody()).path("data").path("slots");
        assertThat(slotsNode.isArray())
                .as("slots field must be an array")
                .isTrue();
        assertThat(slotsNode.size())
                .as("disabled master must have zero bookable slots")
                .isEqualTo(0);
    }

    // ── Test 6: SALON_MASTER PII boundary — cannot view owner-master's booking ─

    @Test
    @DisplayName("GET /bookings/{id} — 403 when SALON_MASTER at same salon reads the owner-master's booking")
    void should_return403_when_salonMasterReadsOwnerMasterBooking() throws Exception {
        // Arrange — create a salon with an owner-master (SALON_OWNER type) and a booking via JDBC.
        // Full booking flow via REST requires slot availability which depends on working-hours
        // and time-zone arithmetic; inserting directly via JDBC is safer for this security pin
        // because we are asserting the canViewBooking rule, not the booking creation logic.
        UUID ownerId = insertUser("pii-owner-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonId = insertSalon(ownerId, "PII Boundary Test Salon");

        // Create the owner-master row (type SALON_OWNER) via JDBC
        UUID ownerMasterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) VALUES (?, ?, ?, 'SALON_OWNER', true, NOW(), NOW())",
                ownerMasterId, ownerId, salonId);

        // Create a client who placed the booking
        UUID clientId = insertUser("pii-client-" + System.nanoTime() + "@beautica.test", "CLIENT");

        // Create a service definition and master_services row so the booking FK is satisfied
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, base_duration_minutes, base_price, buffer_minutes_after, is_active, created_at, updated_at) VALUES (?, 'SALON', ?, 'Massage', 60, 300.00, 0, true, NOW(), NOW())",
                serviceDefId, salonId);
        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, ownerMasterId, serviceDefId);

        // Insert a booking row directly so we control the status without running through the
        // booking creation service (which requires slot availability validation)
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO bookings
                  (id, client_id, master_id, master_service_id, status,
                   starts_at, ends_at, price_at_booking, duration_minutes_at_booking, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'PENDING',
                   NOW() + INTERVAL '1 day', NOW() + INTERVAL '1 day 1 hour',
                   300.00, 60, NOW(), NOW())
                """,
                bookingId, clientId, ownerMasterId, masterServiceId);

        // Create a SALON_MASTER user at the SAME salon (not the booking client, not the owner)
        UUID salonMasterUserId = insertSalonMasterUser(
                "pii-smaster-" + System.nanoTime() + "@beautica.test", salonId);
        String salonMasterToken = loginAndGetToken(
                jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, salonMasterUserId));

        // Act — SALON_MASTER attempts to read the owner-master's booking
        // canViewBooking: for SALON_MASTER, only returns true when masterUserId == actorId.
        // The booking belongs to ownerMasterId (SALON_OWNER user), not salonMasterUserId.
        log.debug("Act: GET {} as SALON_MASTER at same salon — canViewBooking must deny with 403",
                String.format(BOOKING_BY_ID_URL, bookingId));
        ResponseEntity<String> response = restTemplate.exchange(
                String.format(BOOKING_BY_ID_URL, bookingId), HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(salonMasterToken)),
                String.class);

        // Assert
        assertThat(response.getStatusCode())
                .as("SALON_MASTER must not be able to read another master's booking (PII boundary), bookingId=%s",
                        bookingId)
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /**
     * Inserts a user row with the given role and a BCrypt-hashed password, returning the new UUID.
     * {@code email_verified = true} ensures the Phase 1.7 login gate does not block the login.
     */
    private UUID insertUser(String email, String role) {
        String hash = passwordEncoder.encode(TEST_PASSWORD);
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) VALUES (?, ?, ?, ?, true, true)",
                id, email, hash, role);
        return id;
    }

    /**
     * Inserts a {@code SALON_ADMIN} user bound to the given salon via the {@code salon_id} FK.
     * Returns the inserted user UUID.
     */
    private UUID insertSalonAdminUser(String email, UUID salonId) {
        String hash = passwordEncoder.encode(TEST_PASSWORD);
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, salon_id, is_active, email_verified) VALUES (?, ?, ?, 'SALON_ADMIN', ?, true, true)",
                id, email, hash, salonId);
        return id;
    }

    /**
     * Inserts a {@code SALON_MASTER} user bound to the given salon, plus its companion
     * {@code masters} row. Returns the inserted user UUID.
     */
    private UUID insertSalonMasterUser(String email, UUID salonId) {
        String hash = passwordEncoder.encode(TEST_PASSWORD);
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, salon_id, is_active, email_verified) VALUES (?, ?, ?, 'SALON_MASTER', ?, true, true)",
                userId, email, hash, salonId);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) VALUES (?, ?, ?, 'SALON_MASTER', true, NOW(), NOW())",
                masterId, userId, salonId);
        return userId;
    }

    /**
     * Inserts a salon owned by the given user and returns the salon UUID.
     */
    private UUID insertSalon(UUID ownerId, String name) {
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at) VALUES (?, ?, ?, true, NOW(), NOW())",
                salonId, ownerId, name);
        return salonId;
    }

    private String loginAndGetToken(String email) throws Exception {
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, TEST_PASSWORD), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = objectMapper.readValue(resp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        return body.data().accessToken();
    }

    private HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
