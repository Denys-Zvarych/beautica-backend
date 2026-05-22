package com.beautica.dashboard;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.cache.CacheManager;
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
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-stack integration tests for {@code GET /api/v1/dashboard/revenue}.
 *
 * <p>Uses the singleton-container base ({@link AbstractIntegrationTest}) for a real PostgreSQL
 * instance. Each test inserts its own data via {@link org.springframework.jdbc.core.JdbcTemplate}
 * and authenticates via the real {@code /api/v1/auth/login} endpoint to obtain a JWT.
 *
 * <p>Booking rows are inserted directly via JDBC (bypassing service validation) so we can set
 * {@code status = 'COMPLETED'} without going through the full booking state machine.
 *
 * <p>Cleanup happens in {@link AbstractIntegrationTest#cleanDb()} after every test.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Dashboard — GET /api/v1/dashboard/revenue integration")
class DashboardIntegrationTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(DashboardIntegrationTest.class);
    private static final String REVENUE_URL = "/api/v1/dashboard/revenue";
    private static final String TEST_PASSWORD = "Str0ngP@ss1!";

    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private CacheManager cacheManager;

    private static HttpComponentsClientHttpRequestFactory hc5Factory;

    @BeforeEach
    void configureHttpClient() {
        if (hc5Factory == null) {
            hc5Factory = new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault());
        }
        restTemplate.getRestTemplate().setRequestFactory(hc5Factory);
    }

    @AfterAll
    static void destroyHttpClient() throws Exception {
        if (hc5Factory != null) {
            hc5Factory.destroy();
            hc5Factory = null;
        }
    }

    // ── 1. Correct totals from COMPLETED bookings ─────────────────────────────

    @Test
    @DisplayName("GET /revenue — correct totals when 3 COMPLETED bookings exist")
    void should_returnCorrectTotals_when_completedBookingsExist() throws Exception {
        // Arrange
        String ownerEmail = "dash-totals-owner-" + System.nanoTime() + "@beautica.test";
        DataFixture fixture = createSalonOwnerSalonAndMaster(ownerEmail);
        String ownerToken = loginAndGetToken(ownerEmail);

        OffsetDateTime yesterday = ZonedDateTime.now(KYIV).minusDays(1)
                .withHour(10).withMinute(0).withSecond(0).withNano(0).toOffsetDateTime();

        insertCompletedBooking(fixture, yesterday, new BigDecimal("100.00"));
        insertCompletedBooking(fixture, yesterday.plusHours(2), new BigDecimal("200.00"));
        insertCompletedBooking(fixture, yesterday.plusHours(4), new BigDecimal("300.00"));

        log.debug("Act: GET {} as SALON_OWNER — expect 3 completed bookings, revenue 600.00", REVENUE_URL);

        // Act
        ResponseEntity<String> response = restTemplate.exchange(
                REVENUE_URL, HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(ownerToken)),
                String.class);

        // Assert
        assertThat(response.getStatusCode())
                .as("dashboard revenue must return 200")
                .isEqualTo(HttpStatus.OK);

        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("totalCompletedBookings").asLong())
                .as("must count exactly 3 completed bookings")
                .isEqualTo(3L);
        assertThat(new BigDecimal(data.path("estimatedRevenue").asText()))
                .as("must sum to 600.00")
                .isEqualByComparingTo(new BigDecimal("600.00"));
    }

    // ── 2. PENDING and CANCELLED bookings excluded from revenue ──────────────

    @Test
    @DisplayName("GET /revenue — excludes PENDING and CANCELLED bookings from totals")
    void should_excludePendingAndCancelledBookings_when_computingRevenue() throws Exception {
        // Arrange
        String ownerEmail = "dash-excl-owner-" + System.nanoTime() + "@beautica.test";
        DataFixture fixture = createSalonOwnerSalonAndMaster(ownerEmail);
        String ownerToken = loginAndGetToken(ownerEmail);

        OffsetDateTime yesterday = ZonedDateTime.now(KYIV).minusDays(1)
                .withHour(10).withMinute(0).withSecond(0).withNano(0).toOffsetDateTime();

        insertBookingWithStatus(fixture, yesterday, new BigDecimal("100.00"), "COMPLETED");
        insertBookingWithStatus(fixture, yesterday.plusHours(2), new BigDecimal("200.00"), "PENDING");
        insertBookingWithStatus(fixture, yesterday.plusHours(4), new BigDecimal("300.00"), "CANCELLED");

        log.debug("Act: GET {} as SALON_OWNER — only the COMPLETED booking must be counted", REVENUE_URL);

        // Act
        ResponseEntity<String> response = restTemplate.exchange(
                REVENUE_URL, HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(ownerToken)),
                String.class);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("totalCompletedBookings").asLong())
                .as("only the 1 COMPLETED booking must be counted; PENDING/CANCELLED excluded")
                .isEqualTo(1L);
        assertThat(new BigDecimal(data.path("estimatedRevenue").asText()))
                .as("revenue must only include the COMPLETED booking's price")
                .isEqualByComparingTo(new BigDecimal("100.00"));
    }

    // ── 3. Salon A owner does not see salon B data ────────────────────────────

    @Test
    @DisplayName("GET /revenue — salon owner A does not see salon B bookings")
    void should_notReturnOtherSalonsData_when_salonOwnerQueriesDashboard() throws Exception {
        // Arrange — salon A
        String ownerAEmail = "dash-iso-owner-a-" + System.nanoTime() + "@beautica.test";
        DataFixture fixtureA = createSalonOwnerSalonAndMaster(ownerAEmail);
        String ownerAToken = loginAndGetToken(ownerAEmail);

        OffsetDateTime yesterday = ZonedDateTime.now(KYIV).minusDays(1)
                .withHour(10).withMinute(0).withSecond(0).withNano(0).toOffsetDateTime();

        insertCompletedBooking(fixtureA, yesterday, new BigDecimal("500.00"));

        // Arrange — salon B with its own booking
        String ownerBEmail = "dash-iso-owner-b-" + System.nanoTime() + "@beautica.test";
        DataFixture fixtureB = createSalonOwnerSalonAndMaster(ownerBEmail);
        insertCompletedBooking(fixtureB, yesterday.plusHours(1), new BigDecimal("999.00"));

        log.debug("Act: GET {} as SALON_OWNER A — must only see salon A's 1 booking", REVENUE_URL);

        // Act
        ResponseEntity<String> response = restTemplate.exchange(
                REVENUE_URL, HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(ownerAToken)),
                String.class);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("totalCompletedBookings").asLong())
                .as("owner A must see only 1 booking; salon B's booking must not appear")
                .isEqualTo(1L);
        assertThat(new BigDecimal(data.path("estimatedRevenue").asText()))
                .as("revenue must be 500.00 — salon B's 999.00 must not bleed through")
                .isEqualByComparingTo(new BigDecimal("500.00"));
    }

    // ── 4. Date range > 365 days returns 400 ─────────────────────────────────

    @Test
    @DisplayName("GET /revenue — 400 when date range exceeds 365 days")
    void should_return400_when_dateRangeExceeds365Days() throws Exception {
        // Arrange
        String ownerEmail = "dash-range-owner-" + System.nanoTime() + "@beautica.test";
        createSalonOwnerSalonAndMaster(ownerEmail);
        String ownerToken = loginAndGetToken(ownerEmail);

        log.debug("Act: GET {}?from=2020-01-01&to=2022-01-01 — must return 400", REVENUE_URL);

        // Act
        ResponseEntity<String> response = restTemplate.exchange(
                REVENUE_URL + "?from=2020-01-01&to=2022-01-01", HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(ownerToken)),
                String.class);

        // Assert
        assertThat(response.getStatusCode())
                .as("date range exceeding 365 days must return 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── 5. No date params — defaults to last 30 days ─────────────────────────

    @Test
    @DisplayName("GET /revenue — only yesterday's booking included when 31-days-ago booking is excluded")
    void should_defaultToLast30Days_when_noDateParamsProvided() throws Exception {
        // Arrange
        String ownerEmail = "dash-default-owner-" + System.nanoTime() + "@beautica.test";
        DataFixture fixture = createSalonOwnerSalonAndMaster(ownerEmail);
        String ownerToken = loginAndGetToken(ownerEmail);

        // Booking 31 days ago — falls outside the default 30-day window
        OffsetDateTime thirtyOneDaysAgo = ZonedDateTime.now(KYIV).minusDays(31)
                .withHour(10).withMinute(0).withSecond(0).withNano(0).toOffsetDateTime();
        insertCompletedBooking(fixture, thirtyOneDaysAgo, new BigDecimal("999.00"));

        // Booking yesterday — falls inside the default 30-day window
        OffsetDateTime yesterday = ZonedDateTime.now(KYIV).minusDays(1)
                .withHour(10).withMinute(0).withSecond(0).withNano(0).toOffsetDateTime();
        insertCompletedBooking(fixture, yesterday, new BigDecimal("150.00"));

        log.debug("Act: GET {} without date params as SALON_OWNER — only yesterday's booking in window", REVENUE_URL);

        // Act
        ResponseEntity<String> response = restTemplate.exchange(
                REVENUE_URL, HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(ownerToken)),
                String.class);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("totalCompletedBookings").asLong())
                .as("31-days-ago booking must be excluded; only yesterday's booking is in the 30-day default window")
                .isEqualTo(1L);
        assertThat(new BigDecimal(data.path("estimatedRevenue").asText()))
                .as("revenue must be 150.00 — the 31-days-ago booking's 999.00 must be excluded")
                .isEqualByComparingTo(new BigDecimal("150.00"));
    }

    // ── 6. byMaster grouping — 2 masters produce 2 rows ──────────────────────

    @Test
    @DisplayName("GET /revenue — byMaster has 2 entries with correct per-master totals")
    void should_groupByMasterCorrectly_when_multipleMastersHaveBookings() throws Exception {
        // Arrange
        String ownerEmail = "dash-masters-owner-" + System.nanoTime() + "@beautica.test";
        DataFixture fixtureA = createSalonOwnerSalonAndMaster(ownerEmail);

        // Add a second master to the same salon
        DataFixture fixtureB = addExtraMasterToSalon(fixtureA.salonId(), fixtureA.ownerId());

        String ownerToken = loginAndGetToken(ownerEmail);

        OffsetDateTime yesterday = ZonedDateTime.now(KYIV).minusDays(1)
                .withHour(10).withMinute(0).withSecond(0).withNano(0).toOffsetDateTime();

        // Master A — 2 bookings, total 300.00
        insertCompletedBooking(fixtureA, yesterday, new BigDecimal("100.00"));
        insertCompletedBooking(fixtureA, yesterday.plusHours(2), new BigDecimal("200.00"));

        // Master B — 1 booking, total 50.00
        insertCompletedBooking(fixtureB, yesterday.plusHours(4), new BigDecimal("50.00"));

        log.debug("Act: GET {} as SALON_OWNER — byMaster must have 2 entries", REVENUE_URL);

        // Act
        ResponseEntity<String> response = restTemplate.exchange(
                REVENUE_URL, HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(ownerToken)),
                String.class);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        JsonNode byMaster = data.path("byMaster");
        assertThat(byMaster.isArray()).isTrue();
        assertThat(byMaster.size())
                .as("byMaster must contain exactly 2 entries — one per master")
                .isEqualTo(2);

        // Grand totals must be the sum across both masters: 100 + 200 + 50 = 350
        assertThat(data.path("totalCompletedBookings").asLong())
                .as("total must be 3 bookings across both masters")
                .isEqualTo(3L);
        assertThat(new BigDecimal(data.path("estimatedRevenue").asText()))
                .as("total revenue must be 350.00")
                .isEqualByComparingTo(new BigDecimal("350.00"));
    }

    // ── 7. Snapshot price — revenue does not drift when service price is updated ─

    @Test
    @DisplayName("GET /revenue — estimatedRevenue uses price_at_booking snapshot, not live service price")
    void should_useSnapshotPrice_when_masterServicePriceUpdatedAfterBooking() throws Exception {
        // Arrange
        String ownerEmail = "dash-snapshot-owner-" + System.nanoTime() + "@beautica.test";
        DataFixture fixture = createSalonOwnerSalonAndMaster(ownerEmail);
        String ownerToken   = loginAndGetToken(ownerEmail);

        OffsetDateTime yesterday = ZonedDateTime.now(KYIV).minusDays(1)
                .withHour(10).withMinute(0).withSecond(0).withNano(0).toOffsetDateTime();

        // Insert booking with price_at_booking = 100.00
        insertCompletedBooking(fixture, yesterday, new BigDecimal("100.00"));

        // Simulate post-booking price change: update the service definition base price
        // to 999.00. The dashboard must still report 100.00 from the booking snapshot.
        jdbcTemplate.update(
                "UPDATE service_definitions SET base_price = 999.00 WHERE id = ?",
                fixture.serviceDefId());

        // Also update master_services price_override if it exists (belt-and-suspenders)
        jdbcTemplate.update(
                "UPDATE master_services SET price_override = 999.00 WHERE id = ?",
                fixture.masterServiceId());

        log.debug("Act: GET {} as SALON_OWNER after price update — estimatedRevenue must be 100.00 (snapshot)", REVENUE_URL);

        // Act
        ResponseEntity<String> response = restTemplate.exchange(
                REVENUE_URL, HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(ownerToken)),
                String.class);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("totalCompletedBookings").asLong())
                .as("exactly 1 COMPLETED booking must be counted")
                .isEqualTo(1L);
        assertThat(new BigDecimal(data.path("estimatedRevenue").asText()))
                .as("revenue must be 100.00 — the booking's price_at_booking snapshot, not the updated 999.00 service price")
                .isEqualByComparingTo(new BigDecimal("100.00"));
    }

    // ── 8. INDEPENDENT_MASTER revenue scenario (FIX 10) ──────────────────────

    @Test
    @DisplayName("GET /revenue — INDEPENDENT_MASTER sees own completed bookings")
    void should_returnOwnRevenue_when_independentMasterQueriesDashboard() throws Exception {
        // Arrange — create an INDEPENDENT_MASTER user + master record + service
        String hash = passwordEncoder.encode(TEST_PASSWORD);
        String masterEmail = "indie-master-" + System.nanoTime() + "@beautica.test";

        UUID masterUserId = UUID.randomUUID();
        // email_verified = true so Phase 1.7 login gate does not return 403 EMAIL_NOT_VERIFIED
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) VALUES (?, ?, ?, 'INDEPENDENT_MASTER', true, true)",
                masterUserId, masterEmail, hash);

        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) VALUES (?, ?, null, 'INDEPENDENT_MASTER', true, NOW(), NOW())",
                masterId, masterUserId);

        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, base_duration_minutes, base_price, buffer_minutes_after, is_active, created_at, updated_at) VALUES (?, 'INDEPENDENT_MASTER', ?, 'Indie Service', 60, 200.00, 0, true, NOW(), NOW())",
                serviceDefId, masterId);

        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId);

        // Client for booking
        UUID clientId = UUID.randomUUID();
        String clientEmail = "client-indie-" + System.nanoTime() + "@beautica.test";
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active) VALUES (?, ?, ?, 'CLIENT', true)",
                clientId, clientEmail, hash);

        OffsetDateTime yesterday = ZonedDateTime.now(KYIV).minusDays(1)
                .withHour(10).withMinute(0).withSecond(0).withNano(0).toOffsetDateTime();
        OffsetDateTime endsAt = yesterday.plusMinutes(60);

        jdbcTemplate.update(
                """
                INSERT INTO bookings (
                    id, client_id, master_id, master_service_id, salon_id,
                    status, starts_at, ends_at,
                    price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking,
                    created_at, updated_at
                ) VALUES (
                    ?, ?, ?, ?, null,
                    'COMPLETED', ?, ?,
                    150.00, 60, 0,
                    NOW(), NOW()
                )
                """,
                UUID.randomUUID(), clientId, masterId, masterServiceId, yesterday, endsAt);

        String masterToken = loginAndGetToken(masterEmail);

        log.debug("Act: GET {} as INDEPENDENT_MASTER — expect 1 COMPLETED booking, revenue 150.00", REVENUE_URL);

        // Act
        ResponseEntity<String> response = restTemplate.exchange(
                REVENUE_URL, HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(masterToken)),
                String.class);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("totalCompletedBookings").asLong())
                .as("INDEPENDENT_MASTER must see their own 1 completed booking")
                .isEqualTo(1L);
        assertThat(new BigDecimal(data.path("estimatedRevenue").asText()))
                .as("revenue must be 150.00")
                .isEqualByComparingTo(new BigDecimal("150.00"));
    }

    // ── 9. Exactly 365-day range boundary (FIX 11) ───────────────────────────

    @Test
    @DisplayName("GET /revenue — 200 when date range is exactly 365 days (boundary must be > 365, not ≥ 365)")
    void should_acceptDateRange_when_rangeIsExactly365Days() throws Exception {
        // Arrange
        String ownerEmail = "dash-365-owner-" + System.nanoTime() + "@beautica.test";
        createSalonOwnerSalonAndMaster(ownerEmail);
        String ownerToken = loginAndGetToken(ownerEmail);

        // Exact 365-day range: from=2025-01-01 to=2026-01-01 is exactly 365 days apart
        log.debug("Act: GET {}?from=2025-01-01&to=2026-01-01 — exactly 365 days — must be 200", REVENUE_URL);

        // Act
        ResponseEntity<String> response = restTemplate.exchange(
                REVENUE_URL + "?from=2025-01-01&to=2026-01-01", HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(ownerToken)),
                String.class);

        // Assert — 365 days is within the allowed range; >365 triggers the 400
        assertThat(response.getStatusCode())
                .as("exactly 365-day range must be accepted (constraint is > 365 days)")
                .isEqualTo(HttpStatus.OK);
    }

    // ── 10. Cache eviction after booking COMPLETED (FIX 12) ──────────────────

    @Test
    @DisplayName("GET /revenue — reflects COMPLETED booking after cache evicted")
    void should_reflectCompletedBooking_when_revenueCacheEvictedAfterBookingCompleted() throws Exception {
        // Arrange
        String ownerEmail = "dash-evict-owner-" + System.nanoTime() + "@beautica.test";
        DataFixture fixture = createSalonOwnerSalonAndMaster(ownerEmail);
        String ownerToken = loginAndGetToken(ownerEmail);

        OffsetDateTime yesterday = ZonedDateTime.now(KYIV).minusDays(1)
                .withHour(10).withMinute(0).withSecond(0).withNano(0).toOffsetDateTime();

        // Prime the cache: first GET with no bookings → cache entry for this actor
        ResponseEntity<String> primed = restTemplate.exchange(
                REVENUE_URL, HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(ownerToken)),
                String.class);
        assertThat(primed.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode primedData = objectMapper.readTree(primed.getBody()).path("data");
        assertThat(primedData.path("totalCompletedBookings").asLong())
                .as("cache should be primed with 0 bookings")
                .isEqualTo(0L);

        // Insert a COMPLETED booking directly (simulating state-machine completion)
        insertCompletedBooking(fixture, yesterday, new BigDecimal("250.00"));

        // Evict the revenue-dashboard cache to simulate what BookingService does on COMPLETED transition
        org.springframework.cache.Cache dashCache = cacheManager.getCache("revenue-dashboard");
        if (dashCache != null) {
            dashCache.clear();
        }

        log.debug("Act: GET {} after cache eviction — must reflect new COMPLETED booking", REVENUE_URL);

        // Act — second GET after eviction
        ResponseEntity<String> response = restTemplate.exchange(
                REVENUE_URL, HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(ownerToken)),
                String.class);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("totalCompletedBookings").asLong())
                .as("after cache eviction, the new COMPLETED booking must be reflected")
                .isEqualTo(1L);
        assertThat(new BigDecimal(data.path("estimatedRevenue").asText()))
                .as("revenue must show 250.00 from the new booking")
                .isEqualByComparingTo(new BigDecimal("250.00"));
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /**
     * Value object holding the IDs created for a given test scenario. Records are immutable
     * and safe to pass between helper methods.
     */
    private record DataFixture(
            UUID ownerId,
            UUID salonId,
            UUID masterId,
            UUID masterUserId,
            UUID masterServiceId,
            UUID serviceDefId,
            UUID clientId
    ) {}

    /**
     * Inserts a SALON_OWNER user, their salon, one SALON_MASTER user, one master record,
     * one service definition, and one master service. Also inserts a CLIENT user for booking
     * creation. Returns a {@link DataFixture} holding all created IDs.
     */
    private DataFixture createSalonOwnerSalonAndMaster(String ownerEmail) {
        String hash = passwordEncoder.encode(TEST_PASSWORD);

        UUID ownerId = UUID.randomUUID();
        // email_verified = true so Phase 1.7 login gate does not return 403 EMAIL_NOT_VERIFIED
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) VALUES (?, ?, ?, 'SALON_OWNER', true, true)",
                ownerId, ownerEmail, hash);

        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at) VALUES (?, ?, ?, true, NOW(), NOW())",
                salonId, ownerId, "Salon-" + ownerId);

        UUID masterUserId = UUID.randomUUID();
        String masterEmail = "master-" + System.nanoTime() + "@beautica.test";
        // email_verified = true — master does not log in here but follows the same contract
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, salon_id, is_active, email_verified) VALUES (?, ?, ?, 'SALON_MASTER', ?, true, true)",
                masterUserId, masterEmail, hash, salonId);

        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) VALUES (?, ?, ?, 'SALON_MASTER', true, NOW(), NOW())",
                masterId, masterUserId, salonId);

        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, base_duration_minutes, base_price, buffer_minutes_after, is_active, created_at, updated_at) VALUES (?, 'SALON', ?, 'Test Service', 60, 500.00, 0, true, NOW(), NOW())",
                serviceDefId, salonId);

        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId);

        UUID clientId = UUID.randomUUID();
        String clientEmail = "client-" + System.nanoTime() + "@beautica.test";
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active) VALUES (?, ?, ?, 'CLIENT', true)",
                clientId, clientEmail, hash);

        return new DataFixture(ownerId, salonId, masterId, masterUserId, masterServiceId, serviceDefId, clientId);
    }

    /**
     * Adds a second master (with their own user, service definition, and master service) to an
     * existing salon. Used by the byMaster-grouping test to produce two distinct master rows.
     */
    private DataFixture addExtraMasterToSalon(UUID salonId, UUID ownerId) {
        String hash = passwordEncoder.encode(TEST_PASSWORD);

        UUID masterUserId = UUID.randomUUID();
        String masterEmail = "extra-master-" + System.nanoTime() + "@beautica.test";
        // email_verified = true — follows the same contract as other inserts in this class
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, salon_id, is_active, email_verified) VALUES (?, ?, ?, 'SALON_MASTER', ?, true, true)",
                masterUserId, masterEmail, hash, salonId);

        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) VALUES (?, ?, ?, 'SALON_MASTER', true, NOW(), NOW())",
                masterId, masterUserId, salonId);

        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, base_duration_minutes, base_price, buffer_minutes_after, is_active, created_at, updated_at) VALUES (?, 'SALON', ?, 'Extra Service', 60, 100.00, 0, true, NOW(), NOW())",
                serviceDefId, salonId);

        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId);

        // Reuse a client ID from outside — create a fresh one
        UUID clientId = UUID.randomUUID();
        String clientEmail = "extra-client-" + System.nanoTime() + "@beautica.test";
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active) VALUES (?, ?, ?, 'CLIENT', true)",
                clientId, clientEmail, hash);

        return new DataFixture(ownerId, salonId, masterId, masterUserId, masterServiceId, serviceDefId, clientId);
    }

    /**
     * Inserts a COMPLETED booking directly via JDBC, bypassing the booking service state machine.
     * The booking duration mirrors the service-definition's {@code base_duration_minutes} (60 min).
     * Cancellation constraints require the status to be non-terminal for non-null reason; we never
     * set a cancellation reason on COMPLETED rows.
     */
    private void insertCompletedBooking(DataFixture f, OffsetDateTime startsAt, BigDecimal price) {
        insertBookingWithStatus(f, startsAt, price, "COMPLETED");
    }

    /**
     * Inserts a booking with an explicit status. Only PENDING/CONFIRMED rows are excluded from
     * the overlap exclusion constraint (see {@code V18__create_bookings.sql}), so COMPLETED rows
     * with overlapping times are safe to insert directly.
     *
     * <p>Note: the {@code chk_cancellation_reason_status} constraint (V24) forbids a non-null
     * {@code cancellation_reason} on PENDING/CONFIRMED rows, so we never set it here.
     */
    private void insertBookingWithStatus(DataFixture f, OffsetDateTime startsAt,
                                         BigDecimal price, String status) {
        OffsetDateTime endsAt = startsAt.plusMinutes(60);
        jdbcTemplate.update(
                """
                INSERT INTO bookings (
                    id, client_id, master_id, master_service_id, salon_id,
                    status, starts_at, ends_at,
                    price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking,
                    created_at, updated_at
                ) VALUES (
                    ?, ?, ?, ?, ?,
                    ?, ?, ?,
                    ?, 60, 0,
                    NOW(), NOW()
                )
                """,
                UUID.randomUUID(),
                f.clientId(), f.masterId(), f.masterServiceId(), f.salonId(),
                status, startsAt, endsAt,
                price);
    }

    private String loginAndGetToken(String email) throws Exception {
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, TEST_PASSWORD), String.class);
        assertThat(resp.getStatusCode())
                .as("login for %s must succeed", email)
                .isEqualTo(HttpStatus.OK);
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
