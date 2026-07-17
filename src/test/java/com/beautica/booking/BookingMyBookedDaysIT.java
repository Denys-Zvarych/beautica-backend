package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.Role;
import com.beautica.booking.service.BookingService;
import com.beautica.common.TimeZones;
import com.beautica.config.TestSecurityConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA-authored regression suite for Phase 26.5 (backend-qa, 2026-07-17): {@code GET
 * /bookings/me/booked-days}, exercised over the FULL HTTP stack — real Spring Security, a real
 * {@link com.beautica.booking.service.BookingService}, and a real Postgres Testcontainers
 * instance.
 *
 * <p><b>Why this suite exists.</b> The 17 tests backend-dev wrote for this phase
 * ({@code BookingServiceTest}'s {@code getMyBookedDays} group and {@code BookingControllerTest}'s
 * {@code /me/booked-days} group) are unit tests against a mocked {@code BookingRepository} and a
 * {@code @WebMvcTest} slice with a mocked {@code BookingService}. They correctly pin the Java-side
 * {@code from}/{@code to} → {@code OffsetDateTime} conversion and the role-dispatch/validation
 * wiring, but neither can execute the three native queries' {@code SELECT DISTINCT (b.starts_at AT
 * TIME ZONE 'Europe/Kyiv')::date} expression — that SQL only runs against a real Postgres. This
 * suite is the first (and, at merge time, only) real-DB proof that the Kyiv-local grouping, the
 * half-open {@code starts_at < :toExclusive} bound, and all three role-scoped native queries
 * ({@code findBookedDatesByMasterId}/{@code BySalonIds}/{@code ByClientId}) actually do what the
 * phase doc claims.
 *
 * <p>Covers five areas:
 * <ol>
 *   <li><b>Kyiv-vs-UTC date grouping (the critical one)</b> — a booking whose UTC calendar date
 *       differs from its Kyiv calendar date must be reported on the Kyiv day. Proven in both a
 *       summer (EEST, UTC+3) and a winter (EET, UTC+2) case so a hypothetical fixed-offset
 *       regression (as opposed to the correct DST-aware named-zone {@code AT TIME ZONE
 *       'Europe/Kyiv'}) would be caught in at least one season.</li>
 *   <li><b>Half-open upper-bound boundary</b> — a booking at 23:30 Kyiv on the {@code to} day is
 *       included; one at 00:00 Kyiv on {@code to}+1 day is excluded — the same property {@code
 *       BookingMyBookingsDateRangeFilterIT} (Phase 26.2) pins for the JPQL/Specification paths,
 *       here proven against the native query's own {@code toExclusive} bind parameter.</li>
 *   <li><b>Distinctness + ordering + all three role scopes</b> — multiple same-Kyiv-day bookings
 *       collapse to one date, results are ascending, and INDEPENDENT_MASTER/CLIENT/SALON_OWNER
 *       are each exercised against real seeded data (the provider/salon native paths had zero
 *       real-DB coverage before this suite).</li>
 *   <li><b>Cross-endpoint consistency</b> — for every day in a seeded range, a day is present in
 *       {@code booked-days} if and only if {@code GET /bookings/me?from=D&to=D} is non-empty for
 *       that same day, proving the two endpoints cannot silently diverge on timezone/boundary
 *       handling.</li>
 *   <li><b>Scope isolation</b> — one master's booked days never include a day on which only a
 *       second, unrelated master has a booking.</li>
 * </ol>
 *
 * <p><b>Explicitly out of scope</b> (per user instruction) — DISTINCT/HashAggregate cost at scale
 * (routed to Phase 26.6, backlogged) and {@code resolveActorRole}'s fail-open fragility (a
 * pre-existing INFO finding, already backlogged). This suite tests 26.5's behavioural correctness
 * only.
 */
@Import(TestSecurityConfig.class)
@DisplayName("GET /bookings/me/booked-days — Phase 26.5, full HTTP stack over real Postgres")
class BookingMyBookedDaysIT extends AbstractIntegrationTest {

    private static final String BOOKINGS_URL = "/api/v1/bookings";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private BookingTestFixtures fixtures;

    @BeforeEach
    void configureHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
        fixtures = new BookingTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 1 — Kyiv-vs-UTC date grouping (THE discriminating property of this phase)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("summer (EEST, UTC+3) — a booking at 00:30 Kyiv on day D is reported on D, not "
            + "D-1 (its UTC calendar date). FAILS if the native query grouped by UTC "
            + "(`starts_at::date` with no AT TIME ZONE, or AT TIME ZONE 'UTC') instead of "
            + "AT TIME ZONE 'Europe/Kyiv' — a UTC-grouped query would return D-1, not D")
    void should_attributeEarlyKyivLocalBookingToKyivDay_summer_when_queryingBookedDays() throws Exception {
        String masterEmail = "mbbd-kyiv-summer-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID clientId = fixtures.createUser(
                "mbbd-kyiv-summer-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID serviceId = fixtures.createIndependentMasterService(masterId);

        LocalDate day = LocalDate.of(2031, 7, 10); // July -> Europe/Kyiv observes EEST (UTC+3)
        OffsetDateTime kyivLocalStart = kyiv(day, 0, 30);
        // Fixture sanity: the UTC calendar date must differ from the Kyiv calendar date, or this
        // test cannot discriminate correct Kyiv-zone grouping from a UTC-zone bug.
        assertThat(kyivLocalStart.toInstant().atZone(ZoneOffset.UTC).toLocalDate())
                .as("fixture sanity: 00:30 Kyiv on %s must fall on the PREVIOUS UTC calendar day", day)
                .isEqualTo(day.minusDays(1));

        insertBooking(clientId, masterId, serviceId, null, kyivLocalStart);

        List<LocalDate> bookedDays = callBookedDays(fixtures.tokenFor(masterEmail), day.minusDays(2), day.plusDays(2));

        assertThat(bookedDays)
                .as("the discriminating property: grouping by AT TIME ZONE 'Europe/Kyiv' puts this "
                        + "booking on %s; a UTC-grouped query would wrongly put it on %s", day, day.minusDays(1))
                .containsExactly(day)
                .doesNotContain(day.minusDays(1));
    }

    @Test
    @DisplayName("winter (EET, UTC+2) — same discriminating property as the summer case, pinning "
            + "that the zone conversion is DST-aware (a real named zone), not a hardcoded +3 or "
            + "+2 hour offset that would only be correct in one season")
    void should_attributeEarlyKyivLocalBookingToKyivDay_winter_when_queryingBookedDays() throws Exception {
        String masterEmail = "mbbd-kyiv-winter-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID clientId = fixtures.createUser(
                "mbbd-kyiv-winter-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID serviceId = fixtures.createIndependentMasterService(masterId);

        LocalDate day = LocalDate.of(2031, 1, 15); // January -> Europe/Kyiv observes EET (UTC+2)
        OffsetDateTime kyivLocalStart = kyiv(day, 0, 30);
        assertThat(kyivLocalStart.toInstant().atZone(ZoneOffset.UTC).toLocalDate())
                .as("fixture sanity: 00:30 Kyiv on %s must fall on the PREVIOUS UTC calendar day", day)
                .isEqualTo(day.minusDays(1));
        assertThat(kyivLocalStart.getOffset())
                .as("fixture sanity: January must resolve to the winter (+02:00) offset, distinct "
                        + "from the summer case's +03:00, or the two tests would not actually probe "
                        + "different DST branches")
                .isEqualTo(ZoneOffset.ofHours(2));

        insertBooking(clientId, masterId, serviceId, null, kyivLocalStart);

        List<LocalDate> bookedDays = callBookedDays(fixtures.tokenFor(masterEmail), day.minusDays(2), day.plusDays(2));

        assertThat(bookedDays)
                .as("winter-season proof of the same discriminating property: %s not %s", day, day.minusDays(1))
                .containsExactly(day)
                .doesNotContain(day.minusDays(1));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 2 — half-open upper-bound boundary on the native query's own toExclusive bind
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("to is INCLUSIVE of the whole local day: a 23:30 Kyiv booking on the `to` day is "
            + "reported; a 00:00 Kyiv booking on `to`+1 day is not. Same half-open property "
            + "BookingMyBookingsDateRangeFilterIT (26.2) pins for the JPQL/Specification paths, "
            + "here proven against findBookedDatesByMasterId's own toExclusive bind parameter")
    void should_applyHalfOpenUpperBoundOnNativeQuery_when_queryingBookedDays() throws Exception {
        String masterEmail = "mbbd-halfopen-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID clientId = fixtures.createUser(
                "mbbd-halfopen-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID serviceId = fixtures.createIndependentMasterService(masterId);

        LocalDate to = LocalDate.of(2031, 7, 17);
        // 22:30 (not 23:30) so the 60-minute booking ends at 23:30 — comfortably clear of the
        // `to`+1 00:00 booking below and outside no_overlapping_bookings' exclusion constraint,
        // while still landing deep in the `to` day, well past its own local midnight.
        insertBooking(clientId, masterId, serviceId, null, kyiv(to, 22, 30));
        insertBooking(clientId, masterId, serviceId, null, kyiv(to.plusDays(1), 0, 0));

        List<LocalDate> bookedDays = callBookedDays(fixtures.tokenFor(masterEmail), to.minusDays(3), to);

        assertThat(bookedDays)
                .as("23:30 Kyiv on the `to` day must be INCLUDED (upper bound is `to`+1 day at Kyiv "
                        + "midnight, not `to` itself); 00:00 Kyiv on `to`+1 day must be EXCLUDED (it "
                        + "is exactly the exclusive boundary and, separately, outside the requested "
                        + "range)")
                .containsExactly(to)
                .doesNotContain(to.plusDays(1));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 3 — distinctness + ordering + all three role scopes, real Postgres
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("three bookings on the SAME Kyiv day collapse to exactly one date in the result "
            + "(SELECT DISTINCT works against real Postgres, not just in Java)")
    void should_collapseMultipleBookingsOnSameKyivDayToOneDate_when_queryingBookedDays() throws Exception {
        String masterEmail = "mbbd-distinct-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID clientId = fixtures.createUser(
                "mbbd-distinct-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID serviceId = fixtures.createIndependentMasterService(masterId);

        LocalDate day = LocalDate.of(2031, 8, 20);
        insertBooking(clientId, masterId, serviceId, null, kyiv(day, 9, 0));
        insertBooking(clientId, masterId, serviceId, null, kyiv(day, 14, 0));
        insertBooking(clientId, masterId, serviceId, null, kyiv(day, 20, 0));

        List<LocalDate> bookedDays = callBookedDays(fixtures.tokenFor(masterEmail), day, day);

        assertThat(bookedDays)
                .as("3 bookings on the same Kyiv day must appear exactly once, not 3 times")
                .containsExactly(day);
    }

    @Test
    @DisplayName("results are ascending regardless of insertion order")
    void should_returnDatesInAscendingOrder_when_queryingBookedDays() throws Exception {
        String masterEmail = "mbbd-ascending-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID clientId = fixtures.createUser(
                "mbbd-ascending-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID serviceId = fixtures.createIndependentMasterService(masterId);

        LocalDate base = LocalDate.of(2031, 9, 1);
        // Deliberately inserted out of chronological order.
        insertBooking(clientId, masterId, serviceId, null, kyiv(base.plusDays(7), 10, 0));
        insertBooking(clientId, masterId, serviceId, null, kyiv(base.plusDays(1), 10, 0));
        insertBooking(clientId, masterId, serviceId, null, kyiv(base.plusDays(4), 10, 0));

        List<LocalDate> bookedDays = callBookedDays(fixtures.tokenFor(masterEmail), base, base.plusDays(10));

        assertThat(bookedDays)
                .as("dates must come back ascending, independent of insertion order")
                .containsExactly(base.plusDays(1), base.plusDays(4), base.plusDays(7));
    }

    @Test
    @DisplayName("CLIENT scope (findBookedDatesByClientId) — a client sees only their own booked "
            + "days, never another client's, against real seeded data")
    void should_returnClientScopedBookedDays_when_clientQueriesBookedDays() throws Exception {
        String masterEmail = "mbbd-client-scope-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID serviceId = fixtures.createIndependentMasterService(masterId);

        String clientAEmail = "mbbd-client-a-" + System.nanoTime() + "@beautica.test";
        UUID clientAId = fixtures.createUser(clientAEmail, "CLIENT", null);
        UUID clientBId = fixtures.createUser(
                "mbbd-client-b-" + System.nanoTime() + "@beautica.test", "CLIENT", null);

        LocalDate dayA = LocalDate.of(2031, 10, 5);
        LocalDate dayB = LocalDate.of(2031, 10, 9);
        insertBooking(clientAId, masterId, serviceId, null, kyiv(dayA, 11, 0));
        insertBooking(clientBId, masterId, serviceId, null, kyiv(dayB, 11, 0));

        List<LocalDate> bookedDays = callBookedDays(fixtures.tokenFor(clientAEmail), dayA.minusDays(1), dayB.plusDays(1));

        assertThat(bookedDays)
                .as("client A must see only their own booked day, never client B's, even though "
                        + "both bookings are on the same master and within the queried range")
                .containsExactly(dayA)
                .doesNotContain(dayB);
    }

    @Test
    @DisplayName("SALON_OWNER scope (findBookedDatesBySalonIds, salon_id IN) — an owner sees booked "
            + "days across their own salon only, never another owner's salon, against real seeded "
            + "data. The provider/salon native path had ZERO real-DB coverage before this test")
    void should_returnSalonOwnerScopedBookedDays_when_ownerQueriesBookedDays() throws Exception {
        BookingTestFixtures.SalonFixture salonA = fixtures.createSalon("mbbd-owner-a-" + System.nanoTime() + "@beautica.test");
        UUID clientAId = fixtures.createUser(
                "mbbd-owner-a-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID serviceAId = fixtures.createSalonService(salonA.salonId(), salonA.masterId());

        BookingTestFixtures.SalonFixture salonB = fixtures.createSalon("mbbd-owner-b-" + System.nanoTime() + "@beautica.test");
        UUID clientBId = fixtures.createUser(
                "mbbd-owner-b-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID serviceBId = fixtures.createSalonService(salonB.salonId(), salonB.masterId());

        LocalDate dayA = LocalDate.of(2031, 11, 3);
        LocalDate dayB = LocalDate.of(2031, 11, 8);
        insertBooking(clientAId, salonA.masterId(), serviceAId, salonA.salonId(), kyiv(dayA, 12, 0));
        insertBooking(clientBId, salonB.masterId(), serviceBId, salonB.salonId(), kyiv(dayB, 12, 0));

        List<LocalDate> bookedDays = callBookedDays(fixtures.tokenFor(salonA.ownerEmail()), dayA.minusDays(1), dayB.plusDays(1));

        assertThat(bookedDays)
                .as("owner A must see exactly their own salon's booked day, never salon B's, proving "
                        + "the b.salon_id IN (:salonIds) native predicate scopes correctly")
                .containsExactly(dayA)
                .doesNotContain(dayB);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 4 — cross-endpoint consistency: a dot the day-filter can't reproduce is a lie
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("for every day in a seeded range, that day is present in booked-days IF AND ONLY "
            + "IF GET /bookings/me?from=D&to=D is non-empty for the same day — the two endpoints "
            + "must never diverge on timezone/boundary handling")
    void should_agreeWithMyBookingsEndpoint_forDistinctKyivDates() throws Exception {
        String masterEmail = "mbbd-consistency-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID clientId = fixtures.createUser(
                "mbbd-consistency-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID serviceId = fixtures.createIndependentMasterService(masterId);

        LocalDate rangeStart = LocalDate.of(2031, 12, 1);
        LocalDate rangeEnd = rangeStart.plusDays(9); // 10-day range, D0..D9

        // Bookings on D1, D4 (twice, same day), D7 — with gaps deliberately left on D0, D2, D3,
        // D5, D6, D8, D9 to exercise the "absent day -> empty list" side of the property too.
        insertBooking(clientId, masterId, serviceId, null, kyiv(rangeStart.plusDays(1), 9, 0));
        insertBooking(clientId, masterId, serviceId, null, kyiv(rangeStart.plusDays(4), 9, 0));
        insertBooking(clientId, masterId, serviceId, null, kyiv(rangeStart.plusDays(4), 15, 0));
        insertBooking(clientId, masterId, serviceId, null, kyiv(rangeStart.plusDays(7), 9, 0));

        String token = fixtures.tokenFor(masterEmail);
        List<LocalDate> bookedDays = callBookedDays(token, rangeStart, rangeEnd);
        assertThat(bookedDays)
                .as("control assertion: the seeded dot set is exactly D1, D4, D7")
                .containsExactly(rangeStart.plusDays(1), rangeStart.plusDays(4), rangeStart.plusDays(7));

        for (LocalDate day = rangeStart; !day.isAfter(rangeEnd); day = day.plusDays(1)) {
            long totalElements = myBookingsTotalElements(token, day, day);
            if (bookedDays.contains(day)) {
                assertThat(totalElements)
                        .as("day %s has a dot in booked-days, so GET /bookings/me?from=%s&to=%s "
                                + "must be non-empty", day, day, day)
                        .isGreaterThan(0);
            } else {
                assertThat(totalElements)
                        .as("day %s has NO dot in booked-days, so GET /bookings/me?from=%s&to=%s "
                                + "must be empty — a dot the day-filter can't reproduce (or vice "
                                + "versa) is a user-visible lie", day, day, day)
                        .isZero();
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 5 — scope isolation
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("master A's booked days never include a day on which only master B has a booking")
    void should_excludeOtherMastersDays() throws Exception {
        String masterAEmail = "mbbd-isolation-a-" + System.nanoTime() + "@beautica.test";
        UUID masterAId = fixtures.createIndependentMaster(masterAEmail);
        UUID clientAId = fixtures.createUser(
                "mbbd-isolation-a-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID serviceAId = fixtures.createIndependentMasterService(masterAId);

        String masterBEmail = "mbbd-isolation-b-" + System.nanoTime() + "@beautica.test";
        UUID masterBId = fixtures.createIndependentMaster(masterBEmail);
        UUID clientBId = fixtures.createUser(
                "mbbd-isolation-b-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID serviceBId = fixtures.createIndependentMasterService(masterBId);

        LocalDate dayA = LocalDate.of(2032, 1, 6);
        LocalDate dayB = LocalDate.of(2032, 1, 20);
        insertBooking(clientAId, masterAId, serviceAId, null, kyiv(dayA, 10, 0));
        insertBooking(clientBId, masterBId, serviceBId, null, kyiv(dayB, 10, 0));

        List<LocalDate> bookedDaysA = callBookedDays(fixtures.tokenFor(masterAEmail), dayA.minusDays(1), dayB.plusDays(1));

        assertThat(bookedDaysA)
                .as("master A's booked days must include only their own day, never master B's")
                .containsExactly(dayA)
                .doesNotContain(dayB);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 6 — no-N+1 query-count guard (phase doc's "one query per call" acceptance
    // criterion, untestable while the endpoint 500'd — real Postgres via Hibernate
    // Statistics, mirroring FavoriteListProjectionTest / LocalityDiscoveryPerfHardeningTest)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Calls {@link BookingService#getMyBookedDays} directly (not over HTTP) so the measured
     * Hibernate query count reflects only the service's own DB work — not the login round-trip,
     * JWT filter chain, or JSON (de)serialisation that {@link #callBookedDays} would additionally
     * incur. Mirrors {@code FavoriteListProjectionTest}'s direct-service-call convention for the
     * same reason.
     */
    @Autowired
    private BookingService bookingService;

    @Autowired
    private EntityManagerFactory emf;

    private Statistics statistics() {
        Statistics statistics = emf.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        return statistics;
    }

    private static Authentication authFor(Role role) {
        return new UsernamePasswordAuthenticationToken(
                "test@example.com", null, List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
    }

    @Test
    @DisplayName("CLIENT scope (findBookedDatesByClientId) executes EXACTLY ONE Hibernate query per "
            + "call, and that count does NOT grow with the number of booked days returned — the "
            + "phase doc's 'one query per call' acceptance criterion. FAILS if a per-row/N+1 fetch "
            + "is ever introduced (count would scale toward 40, not stay at 1)")
    void should_executeExactlyOneQuery_when_clientHasManyBookedDays() {
        String masterEmail = "mbbd-qcount-client-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID serviceId = fixtures.createIndependentMasterService(masterId);
        UUID clientId = fixtures.createUser(
                "mbbd-qcount-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);

        LocalDate base = LocalDate.of(2033, 2, 1);
        insertBooking(clientId, masterId, serviceId, null, kyiv(base, 10, 0));

        Statistics statistics = statistics();
        statistics.clear();
        List<LocalDate> oneDayResult = bookingService.getMyBookedDays(
                clientId, authFor(Role.CLIENT), base.minusDays(5), base.plusDays(60));
        long queriesForOneDay = statistics.getQueryExecutionCount();

        assertThat(oneDayResult).as("control: exactly 1 booked day seeded so far").containsExactly(base);
        assertThat(queriesForOneDay)
                .as("CLIENT scope must execute exactly ONE query (findBookedDatesByClientId), got %s",
                        queriesForOneDay)
                .isEqualTo(1);

        // Seed 39 MORE booked days (40 total) — same client/master, distinct days/hours so none
        // trip the no_overlapping_bookings exclusion constraint.
        for (int i = 1; i <= 39; i++) {
            insertBooking(clientId, masterId, serviceId, null, kyiv(base.plusDays(i), 10, 0));
        }

        statistics.clear();
        List<LocalDate> fortyDayResult = bookingService.getMyBookedDays(
                clientId, authFor(Role.CLIENT), base.minusDays(5), base.plusDays(60));
        long queriesForFortyDays = statistics.getQueryExecutionCount();

        assertThat(fortyDayResult).as("control: 40 distinct booked days now seeded").hasSize(40);
        assertThat(queriesForFortyDays)
                .as("query count for 40 booked days must be the SAME constant as for 1 (%s) — proves "
                        + "the aggregation does not scale with result size (the actual N+1 guard); a "
                        + "per-row/N+1 fetch would push this toward 40, not leave it at 1",
                        queriesForOneDay)
                .isEqualTo(queriesForOneDay)
                .isEqualTo(1);
    }

    @Test
    @DisplayName("INDEPENDENT_MASTER (provider) scope executes AT MOST TWO Hibernate queries per "
            + "call — masterRepository.findByUserId point-lookup + findBookedDatesByMasterId "
            + "aggregation — and that count does NOT grow with the number of booked days returned. "
            + "Pins the phase doc's 'bounded, at most two queries' bound for the provider/salon path")
    void should_executeAtMostTwoQueries_when_providerHasManyBookedDays() {
        String masterEmail = "mbbd-qcount-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID masterUserId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM masters WHERE id = ?", UUID.class, masterId);
        UUID serviceId = fixtures.createIndependentMasterService(masterId);
        UUID clientId = fixtures.createUser(
                "mbbd-qcount-master-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);

        LocalDate base = LocalDate.of(2033, 3, 1);
        insertBooking(clientId, masterId, serviceId, null, kyiv(base, 10, 0));

        Statistics statistics = statistics();
        statistics.clear();
        List<LocalDate> oneDayResult = bookingService.getMyBookedDays(
                masterUserId, authFor(Role.INDEPENDENT_MASTER), base.minusDays(5), base.plusDays(60));
        long queriesForOneDay = statistics.getQueryExecutionCount();

        assertThat(oneDayResult).as("control: exactly 1 booked day seeded so far").containsExactly(base);
        assertThat(queriesForOneDay)
                .as("provider scope must execute at most TWO queries (master point-lookup + "
                        + "aggregation), got %s", queriesForOneDay)
                .isEqualTo(2);

        for (int i = 1; i <= 39; i++) {
            insertBooking(clientId, masterId, serviceId, null, kyiv(base.plusDays(i), 10, 0));
        }

        statistics.clear();
        List<LocalDate> fortyDayResult = bookingService.getMyBookedDays(
                masterUserId, authFor(Role.INDEPENDENT_MASTER), base.minusDays(5), base.plusDays(60));
        long queriesForFortyDays = statistics.getQueryExecutionCount();

        assertThat(fortyDayResult).as("control: 40 distinct booked days now seeded").hasSize(40);
        assertThat(queriesForFortyDays)
                .as("query count for 40 booked days must be the SAME constant as for 1 (%s) — proves "
                        + "the aggregation does not scale with result size; a per-row/N+1 fetch would "
                        + "push this toward 41, not leave it at 2", queriesForOneDay)
                .isEqualTo(queriesForOneDay)
                .isEqualTo(2);
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    /** Resolves a Kyiv-local wall-clock instant to its UTC-backed {@link OffsetDateTime}. */
    private static OffsetDateTime kyiv(LocalDate date, int hour, int minute) {
        return date.atTime(hour, minute).atZone(TimeZones.KYIV).toOffsetDateTime();
    }

    /**
     * Inserts a CONFIRMED booking row directly via SQL (bypassing the create-booking service flow
     * entirely) so the test can seed an arbitrary {@code starts_at} deterministically. {@code
     * salonId} is nullable — omitted from the INSERT column list for an independent master.
     * Mirrors {@code BookingMyBookingsDateRangeFilterIT#insertBooking} (Phase 26.2).
     */
    private UUID insertBooking(UUID clientId, UUID masterId, UUID masterServiceId, UUID salonId,
                                OffsetDateTime startsAt) {
        UUID bookingId = UUID.randomUUID();
        if (salonId != null) {
            jdbcTemplate.update(
                    "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                            + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                            + "buffer_minutes_at_booking, booking_source, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, 'CONFIRMED', ?, ?, 500.00, 60, 0, 'APP', NOW(), NOW())",
                    bookingId, clientId, masterId, masterServiceId, salonId,
                    startsAt, startsAt.plusMinutes(60));
        } else {
            jdbcTemplate.update(
                    "INSERT INTO bookings (id, client_id, master_id, master_service_id, status, "
                            + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                            + "buffer_minutes_at_booking, booking_source, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, 'CONFIRMED', ?, ?, 500.00, 60, 0, 'APP', NOW(), NOW())",
                    bookingId, clientId, masterId, masterServiceId,
                    startsAt, startsAt.plusMinutes(60));
        }
        return bookingId;
    }

    // ── HTTP helpers ─────────────────────────────────────────────────────────

    private List<LocalDate> callBookedDays(String token, LocalDate from, LocalDate to) throws Exception {
        String url = BOOKINGS_URL + "/me/booked-days?from=" + from + "&to=" + to;
        ResponseEntity<String> resp = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(fixtures.bearerHeaders(token)), String.class);
        assertThat(resp.getStatusCode()).as("booked-days call must succeed").isEqualTo(HttpStatus.OK);
        JsonNode root = objectMapper.readTree(resp.getBody());
        List<LocalDate> dates = new ArrayList<>();
        for (JsonNode node : root.path("data")) {
            dates.add(LocalDate.parse(node.asText()));
        }
        return dates;
    }

    private long myBookingsTotalElements(String token, LocalDate from, LocalDate to) throws Exception {
        String url = BOOKINGS_URL + "/me?from=" + from + "&to=" + to + "&size=50";
        ResponseEntity<String> resp = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(fixtures.bearerHeaders(token)), String.class);
        assertThat(resp.getStatusCode()).as("/me call must succeed").isEqualTo(HttpStatus.OK);
        JsonNode root = objectMapper.readTree(resp.getBody());
        return root.path("data").path("totalElements").asLong();
    }
}
