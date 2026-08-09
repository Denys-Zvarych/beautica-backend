package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.config.TestSecurityConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.security.crypto.password.PasswordEncoder;

import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 29.4 — {@code GET /bookings/me/unclosed-count}, full HTTP stack over real Postgres, fixed
 * {@link Clock}. Proves the count agrees exactly with {@code ?partition=AWAITING_CLOSURE}'s {@code
 * totalElements} for every role, that {@code SALON_MASTER} sees only their own subset, and that
 * the count is one bounded query — never a per-master loop.
 *
 * <p>Own class-local frozen {@link Clock} ({@code NOW = 2031-06-15T12:00:00Z}) — deliberately NOT
 * shared with any sibling suite's {@code FrozenClockConfig} (Phase 28.3's isolation mechanism).
 */
@Import({TestSecurityConfig.class, BookingUnclosedCountIT.FrozenClockConfig.class})
@DisplayName("GET /bookings/me/unclosed-count — Phase 29.4, full HTTP stack over real Postgres, fixed clock")
class BookingUnclosedCountIT extends AbstractIntegrationTest {

    private static final String COUNT_URL = "/api/v1/bookings/me/unclosed-count";
    private static final String BOOKINGS_URL = "/api/v1/bookings";
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2031-06-15T12:00:00Z");

    @TestConfiguration
    static class FrozenClockConfig {
        @Bean
        Clock systemClock() {
            return Clock.fixed(NOW.toInstant(), ZoneOffset.UTC);
        }
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private EntityManagerFactory emf;

    private BookingTestFixtures fixtures;

    @BeforeEach
    void configureHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
        fixtures = new BookingTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
    }

    @Test
    @DisplayName("agreement: unclosed-count equals the totalElements of "
            + "?partition=AWAITING_CLOSURE for INDEPENDENT_MASTER, SALON_OWNER and CLIENT, at the "
            + "same frozen instant")
    void should_matchPartitionTotalElements_when_countedAcrossRoles() throws Exception {
        String masterEmail = "unclosed-agree-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        String clientEmail = "unclosed-agree-client-" + System.nanoTime() + "@beautica.test";
        UUID clientId = fixtures.createUser(clientEmail, "CLIENT", null);
        UUID serviceId = fixtures.createIndependentMasterService(masterId);

        insertBooking(clientId, masterId, serviceId, null, "CONFIRMED", NOW.minusHours(3), NOW.minusHours(2));
        insertBooking(clientId, masterId, serviceId, null, "CONFIRMED", NOW.minusHours(6), NOW.minusHours(5));
        insertBooking(clientId, masterId, serviceId, null, "COMPLETED", NOW.minusHours(9), NOW.minusHours(8));
        insertBooking(clientId, masterId, serviceId, null, "CONFIRMED", NOW.plusHours(1), NOW.plusHours(2));

        String masterToken = fixtures.tokenFor(masterEmail);
        String clientToken = fixtures.tokenFor(clientEmail);

        assertAgreesWithPartition(masterToken);
        assertAgreesWithPartition(clientToken);

        assertThat(count(masterToken)).isEqualTo(2L);
        assertThat(count(clientToken)).isEqualTo(2L);
    }

    private void assertAgreesWithPartition(String token) throws Exception {
        long fromCountEndpoint = count(token);
        long fromPartition = callMe(token, "?partition=AWAITING_CLOSURE&size=1")
                .path("data").path("totalElements").asLong();
        assertThat(fromCountEndpoint)
                .as("unclosed-count must equal ?partition=AWAITING_CLOSURE's totalElements")
                .isEqualTo(fromPartition);
    }

    @Test
    @DisplayName("SALON_MASTER isolation — salon with masters A/B/C holding 2/3/4 unclosed "
            + "bookings: A's count is 2 (not 9), B's is 3, C's is 4, the owner's is 9, a master of a "
            + "DIFFERENT salon sees 0. SALON_ADMIN gets 403 — reusing the SAME provider-scope "
            + "rejection GET /bookings/me already applies to that role, not a new authorization rule")
    void should_isolateCountsPerMaster_when_salonHasMultipleMasters() throws Exception {
        BookingTestFixtures.SalonFixture salon = fixtures.createSalon("unclosed-sm-" + System.nanoTime() + "@beautica.test");
        UUID clientId = fixtures.createUser("unclosed-sm-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);

        UUID serviceA = fixtures.createSalonService(salon.salonId(), salon.masterId());
        seedUnclosed(clientId, salon.masterId(), serviceA, 2);

        String masterBEmail = "unclosed-sm-b-" + System.nanoTime() + "@beautica.test";
        UUID masterBId = addSalonMaster(salon.salonId(), masterBEmail);
        UUID serviceB = fixtures.createSalonService(salon.salonId(), masterBId);
        seedUnclosed(clientId, masterBId, serviceB, 3);

        String masterCEmail = "unclosed-sm-c-" + System.nanoTime() + "@beautica.test";
        UUID masterCId = addSalonMaster(salon.salonId(), masterCEmail);
        UUID serviceC = fixtures.createSalonService(salon.salonId(), masterCId);
        seedUnclosed(clientId, masterCId, serviceC, 4);

        // Admin of the same salon.
        String adminEmail = "unclosed-sm-admin-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(adminEmail, "SALON_ADMIN", salon.salonId());

        // A master of a completely different salon.
        BookingTestFixtures.SalonFixture otherSalon = fixtures.createSalon("unclosed-sm-other-" + System.nanoTime() + "@beautica.test");

        assertThat(count(fixtures.tokenFor(salon.masterEmail())))
                .as("master A's count must be exactly their own 2, not the salon total of 9")
                .isEqualTo(2L);
        assertThat(count(fixtures.tokenFor(masterBEmail)))
                .as("master B's count must be exactly their own 3")
                .isEqualTo(3L);
        assertThat(count(fixtures.tokenFor(masterCEmail)))
                .as("master C's count must be exactly their own 4")
                .isEqualTo(4L);
        assertThat(count(fixtures.tokenFor(salon.ownerEmail())))
                .as("the owner's count must be the salon total: 2 + 3 + 4 = 9")
                .isEqualTo(9L);
        assertThat(count(fixtures.tokenFor(otherSalon.masterEmail())))
                .as("a master of a DIFFERENT salon must see 0, never leaking into this salon's count")
                .isEqualTo(0L);

        // SALON_ADMIN is rejected with the same ForbiddenException listMyBookings/getMyBookedDays
        // already throw for that role on this scope — see BookingService#getUnclosedCount's
        // javadoc for why this endpoint reuses that boundary rather than inventing new admission
        // logic for it.
        ResponseEntity<String> adminResp = restTemplate.exchange(
                COUNT_URL, HttpMethod.GET,
                new HttpEntity<>(fixtures.bearerHeaders(fixtures.tokenFor(adminEmail))), String.class);
        assertThat(adminResp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("CLIENT sees their own unclosed set across multiple providers")
    void should_countAcrossMultipleProviders_when_clientScope() throws Exception {
        String masterAEmail = "unclosed-cli-a-" + System.nanoTime() + "@beautica.test";
        UUID masterAId = fixtures.createIndependentMaster(masterAEmail);
        UUID serviceA = fixtures.createIndependentMasterService(masterAId);
        String masterBEmail = "unclosed-cli-b-" + System.nanoTime() + "@beautica.test";
        UUID masterBId = fixtures.createIndependentMaster(masterBEmail);
        UUID serviceB = fixtures.createIndependentMasterService(masterBId);

        String clientEmail = "unclosed-cli-client-" + System.nanoTime() + "@beautica.test";
        UUID clientId = fixtures.createUser(clientEmail, "CLIENT", null);

        insertBooking(clientId, masterAId, serviceA, null, "CONFIRMED", NOW.minusHours(3), NOW.minusHours(2));
        insertBooking(clientId, masterBId, serviceB, null, "CONFIRMED", NOW.minusHours(5), NOW.minusHours(4));
        insertBooking(clientId, masterBId, serviceB, null, "COMPLETED", NOW.minusHours(9), NOW.minusHours(8));

        assertThat(count(fixtures.tokenFor(clientEmail))).isEqualTo(2L);
    }

    @Test
    @DisplayName("zero case — a provider with no elapsed CONFIRMED rows gets count:0, not a 404")
    void should_returnZero_when_noUnclosedBookingsExist() throws Exception {
        String masterEmail = "unclosed-zero-" + System.nanoTime() + "@beautica.test";
        fixtures.createIndependentMaster(masterEmail);

        ResponseEntity<String> resp = restTemplate.exchange(
                COUNT_URL, HttpMethod.GET,
                new HttpEntity<>(fixtures.bearerHeaders(fixtures.tokenFor(masterEmail))), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(count(fixtures.tokenFor(masterEmail))).isEqualTo(0L);
    }

    @Test
    @DisplayName("boundary — a CONFIRMED booking with endsAt == NOW exactly is NOT counted (strict '<')")
    void should_excludeBoundaryRow_when_endsAtEqualsNow() throws Exception {
        String masterEmail = "unclosed-boundary-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID clientId = fixtures.createUser("unclosed-boundary-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID serviceId = fixtures.createIndependentMasterService(masterId);

        insertBooking(clientId, masterId, serviceId, null, "CONFIRMED", NOW.minusHours(1), NOW);

        assertThat(count(fixtures.tokenFor(masterEmail))).isEqualTo(0L);
    }

    @Test
    @DisplayName("closed rows never contribute: COMPLETED (including a future-endsAt one), "
            + "NOT_COMPLETED, CANCELLED, DECLINED all excluded")
    void should_excludeAllClosedAndCancelledStatuses_when_counting() throws Exception {
        String masterEmail = "unclosed-closed-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID clientId = fixtures.createUser("unclosed-closed-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID serviceId = fixtures.createIndependentMasterService(masterId);

        insertBooking(clientId, masterId, serviceId, null, "COMPLETED", NOW.minusHours(3), NOW.minusHours(2));
        insertBooking(clientId, masterId, serviceId, null, "COMPLETED", NOW.minusHours(6), NOW.plusHours(5));
        insertBooking(clientId, masterId, serviceId, null, "NOT_COMPLETED", NOW.minusHours(8), NOW.minusHours(7));
        insertBooking(clientId, masterId, serviceId, null, "CANCELLED", NOW.plusHours(1), NOW.plusHours(2));
        insertBooking(clientId, masterId, serviceId, null, "DECLINED", NOW.plusHours(3), NOW.plusHours(4));

        assertThat(count(fixtures.tokenFor(masterEmail))).isEqualTo(0L);
    }

    @Test
    @DisplayName("no mutation: reading unclosed-count twice returns the identical number both "
            + "times, and the underlying status column is byte-identical to what was inserted — "
            + "getUnclosedCount is a plain COUNT(*), it must never close a booking as a side effect "
            + "of being read")
    void should_mutateNoRows_when_unclosedCountIsRead() throws Exception {
        String masterEmail = "unclosed-noop-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID clientId = fixtures.createUser("unclosed-noop-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID serviceId = fixtures.createIndependentMasterService(masterId);

        UUID elapsedConfirmedId = insertBooking(clientId, masterId, serviceId, null,
                "CONFIRMED", NOW.minusHours(2), NOW.minusHours(1));
        String masterToken = fixtures.tokenFor(masterEmail);

        assertThat(count(masterToken)).isEqualTo(1L);
        // A second read: a hypothetical sweeper-on-read regression would flip this row to a
        // terminal status on the first call, silently dropping the count to 0 on the second.
        assertThat(count(masterToken))
                .as("a second read must return the identical count — this is the cheap guard "
                        + "against a future edit sneaking a status write into this read path")
                .isEqualTo(1L);

        String statusAfter = jdbcTemplate.queryForObject(
                "SELECT status FROM bookings WHERE id = ?", String.class, elapsedConfirmedId);
        assertThat(statusAfter)
                .as("GET /bookings/me/unclosed-count must never mutate the underlying status column")
                .isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("one bounded query, no N+1: the statement count for a salon-scope request with 3 "
            + "masters is the SAME small bound as a single-master request — never a per-master loop")
    void should_runBoundedStatementCount_when_countingAcrossScopes() throws Exception {
        SessionFactory sessionFactory = emf.unwrap(SessionFactory.class);
        Statistics statistics = sessionFactory.getStatistics();

        // ── master scope ──
        String masterEmail = "unclosed-stmt-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID clientId = fixtures.createUser("unclosed-stmt-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID serviceId = fixtures.createIndependentMasterService(masterId);
        insertBooking(clientId, masterId, serviceId, null, "CONFIRMED", NOW.minusHours(3), NOW.minusHours(2));
        String masterToken = fixtures.tokenFor(masterEmail);

        statistics.setStatisticsEnabled(true);
        statistics.clear();
        assertThat(count(masterToken)).isEqualTo(1L);
        long masterScopeStatements = statistics.getPrepareStatementCount();
        // No hard-coded magic number here — the exact count also includes whatever the security
        // filter chain issues for principal resolution (Phase 29.4's own acceptance criterion
        // says as much), which this test does not try to pin. What it DOES pin, below, is that
        // the SALON scope (three masters) issues the SAME statement count as this ONE-master
        // baseline — the real N+1 trap this endpoint must avoid.

        // ── salon scope, 3 masters ──
        BookingTestFixtures.SalonFixture salon = fixtures.createSalon("unclosed-stmt-salon-" + System.nanoTime() + "@beautica.test");
        UUID serviceA = fixtures.createSalonService(salon.salonId(), salon.masterId());
        insertBooking(clientId, salon.masterId(), serviceA, salon.salonId(), "CONFIRMED", NOW.minusHours(3), NOW.minusHours(2));
        UUID masterBId = addSalonMaster(salon.salonId(), "unclosed-stmt-b-" + System.nanoTime() + "@beautica.test");
        UUID serviceB = fixtures.createSalonService(salon.salonId(), masterBId);
        insertBooking(clientId, masterBId, serviceB, salon.salonId(), "CONFIRMED", NOW.minusHours(3), NOW.minusHours(2));
        UUID masterCId = addSalonMaster(salon.salonId(), "unclosed-stmt-c-" + System.nanoTime() + "@beautica.test");
        UUID serviceC = fixtures.createSalonService(salon.salonId(), masterCId);
        insertBooking(clientId, masterCId, serviceC, salon.salonId(), "CONFIRMED", NOW.minusHours(3), NOW.minusHours(2));
        String ownerToken = fixtures.tokenFor(salon.ownerEmail());

        statistics.clear();
        assertThat(count(ownerToken)).isEqualTo(3L);
        long salonScopeStatements = statistics.getPrepareStatementCount();
        assertThat(salonScopeStatements)
                .as("salon scope with THREE masters must issue the SAME statement count as the "
                        + "single-master baseline (%s) — the N+1 trap this endpoint must avoid is "
                        + "resolving masters first and counting per master, which would make this "
                        + "number scale with 3 instead of staying flat", masterScopeStatements)
                .isEqualTo(masterScopeStatements);
    }

    @Test
    @DisplayName("401 for an unauthenticated request")
    void should_return401_when_unauthenticated() {
        ResponseEntity<String> resp = restTemplate.exchange(
                COUNT_URL, HttpMethod.GET, HttpEntity.EMPTY, String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── fixture + HTTP helpers ───────────────────────────────────────────────────────────────────

    private void seedUnclosed(UUID clientId, UUID masterId, UUID serviceId, int count) {
        for (int i = 0; i < count; i++) {
            insertBooking(clientId, masterId, serviceId, null, "CONFIRMED",
                    NOW.minusDays(i + 1).minusHours(2), NOW.minusDays(i + 1).minusHours(1));
        }
    }

    /** Adds a second (or third) SALON_MASTER to an already-seeded salon — mirrors {@code
     * BookingTestFixtures#createSalon}'s own master-creation SQL for the ONE master it seeds. */
    private UUID addSalonMaster(UUID salonId, String email) {
        UUID masterUserId = fixtures.createUser(email, "SALON_MASTER", salonId);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'SALON_MASTER', true, NOW(), NOW())",
                masterId, masterUserId, salonId);
        return masterId;
    }

    private UUID insertBooking(UUID clientId, UUID masterId, UUID masterServiceId, UUID salonId,
                                String status, OffsetDateTime startsAt, OffsetDateTime endsAt) {
        UUID bookingId = UUID.randomUUID();
        int minutes = (int) Duration.between(startsAt, endsAt).toMinutes();
        if (salonId != null) {
            jdbcTemplate.update(
                    "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                            + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                            + "buffer_minutes_at_booking, booking_source, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 500.00, ?, 0, 'APP', NOW(), NOW())",
                    bookingId, clientId, masterId, masterServiceId, salonId, status, startsAt, endsAt, minutes);
        } else {
            jdbcTemplate.update(
                    "INSERT INTO bookings (id, client_id, master_id, master_service_id, status, "
                            + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                            + "buffer_minutes_at_booking, booking_source, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, 500.00, ?, 0, 'APP', NOW(), NOW())",
                    bookingId, clientId, masterId, masterServiceId, status, startsAt, endsAt, minutes);
        }
        return bookingId;
    }

    private long count(String token) throws Exception {
        ResponseEntity<String> resp = restTemplate.exchange(
                COUNT_URL, HttpMethod.GET, new HttpEntity<>(fixtures.bearerHeaders(token)), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(resp.getBody()).path("data").path("count").asLong();
    }

    private JsonNode callMe(String token, String query) throws Exception {
        ResponseEntity<String> resp = restTemplate.exchange(
                BOOKINGS_URL + "/me" + query, HttpMethod.GET,
                new HttpEntity<>(fixtures.bearerHeaders(token)), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(resp.getBody());
    }
}
