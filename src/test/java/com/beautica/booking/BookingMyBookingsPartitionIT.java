package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.config.TestSecurityConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.impl.classic.HttpClients;
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

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA suite for Phase 28.1–28.3 (backend, {@code docs/backend-phases/phase-216}..{@code -218}):
 * the server-side {@code partition=UPCOMING|PAST|CANCELLED} filter on {@code GET /bookings/me},
 * exercised over the FULL HTTP stack — real Spring Security, a real {@link
 * com.beautica.booking.service.BookingService}, and a real Postgres Testcontainers instance, with
 * a FIXED {@link Clock} bean override so the {@code UPCOMING}/{@code PAST} boundary is
 * deterministic rather than racing the wall clock.
 *
 * <p><b>Fixed-clock isolation.</b> {@link FrozenClockConfig} overrides the {@code systemClock}
 * bean ONLY for this test class's Spring context. Spring's test-context cache keys on the exact
 * set of {@code @Import}s/configuration classes, so a class that does not import {@link
 * FrozenClockConfig} (every sibling {@code BookingMyBookings*IT}, every other booking IT using the
 * real {@code Clock.systemUTC()} bean) gets a genuinely separate cached {@code
 * ApplicationContext} and never sees this frozen clock — the same isolation mechanism {@code
 * MasterBookableDaysCacheIT} already relies on for its own {@code FrozenKyivClockConfig}. Running
 * this class alongside its four siblings in the same {@code ./gradlew test --tests
 * "com.beautica.booking.*"} invocation (see the phase doc) is itself the leak-proof: if the
 * override ever escaped into another class's context, at least one of those real-clock-dependent
 * suites would fail non-deterministically.
 *
 * <p>Covers:
 * <ol>
 *   <li><b>Total, disjoint cover</b> — {@code count(UPCOMING) + count(PAST) + count(CANCELLED) ==
 *       count(unfiltered)} over an eight-row fixture ({@link #seedEightRowFixture}) seeding all
 *       five {@link com.beautica.booking.enums.BookingStatus} values, on both the provider
 *       (INDEPENDENT_MASTER) and CLIENT code paths, plus a lighter SALON_OWNER check. That fixture
 *       spans both sides of {@code now} only for the three {@code CONFIRMED} rows and the two
 *       {@code COMPLETED} rows (see {@link #seedEightRowFixture}'s Option-A hole row) — it seeds
 *       {@code NOT_COMPLETED} only in the past and {@code CANCELLED}/{@code DECLINED} only in the
 *       future. {@link #should_includeInPast_when_notCompletedBookingHasFutureEndsAt} and {@link
 *       #should_includeInCancelled_when_cancelledOrDeclinedBookingEndsAtIsInThePast} close that gap
 *       with dedicated fixtures proving those two statuses' partition assignment is independent of
 *       {@code endsAt} on the side the eight-row fixture doesn't exercise.</li>
 *   <li><b>{@code endsAt == now} boundary</b> — lands in {@code UPCOMING} and only there.</li>
 *   <li><b>The Option-A hole</b> — a {@code COMPLETED} booking with a FUTURE {@code endsAt} (a
 *       provider who closed a visit early) still appears in {@code PAST}.</li>
 *   <li><b>Precedence</b> — {@code partition} present makes {@code status} IGNORED, not a 400.</li>
 *   <li><b>Composition</b> — {@code partition} ANDs correctly with {@code from}/{@code to}/{@code
 *       serviceId}.</li>
 *   <li><b>Pagination</b> — {@code totalElements} and page contents stay correct across a
 *       multi-page {@code partition=PAST} result whose rows alternate between the {@code PAST}
 *       OR's two sub-clauses ({@code COMPLETED} vs. elapsed-unclosed {@code CONFIRMED}) — all 13
 *       seeded rows sit well before {@code now} ({@code NOW.minusDays(30)} onward); nothing in this
 *       fixture straddles the {@code now} boundary itself.</li>
 *   <li><b>Invalid enum</b> — {@code ?partition=BOGUS} is a 400 that does not echo the valid
 *       {@code UPCOMING}/{@code PAST}/{@code CANCELLED} constants.</li>
 * </ol>
 *
 * <p><b>Backwards-compatibility proof (out of THIS file, by design).</b> The five pre-28.1 sibling
 * suites — {@code BookingMyBookingsMultiStatusFilterIT}, {@code BookingMyBookingsDateRangeFilterIT},
 * {@code BookingMyBookingsServiceFilterIT}, {@code BookingMyBookingsSortIT}, {@code
 * BookingMyBookedDaysIT} — are NOT edited by this phase and must keep passing unedited; that is
 * the additive-optional contract, pinned by running them alongside this class rather than by any
 * assertion inside it.
 */
@Import({TestSecurityConfig.class, BookingMyBookingsPartitionIT.FrozenClockConfig.class})
@DisplayName("GET /bookings/me — Phase 28.1 partition filter, full HTTP stack over real Postgres, fixed clock")
class BookingMyBookingsPartitionIT extends AbstractIntegrationTest {

    private static final String BOOKINGS_URL = "/api/v1/bookings";

    /** Frozen instant every request in this class sees as "now" — see the class javadoc. */
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

    private BookingTestFixtures fixtures;

    @BeforeEach
    void configureHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
        fixtures = new BookingTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Total-disjoint-cover + boundary + Option-A hole — provider (INDEPENDENT_MASTER) path
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("INDEPENDENT_MASTER — partition UPCOMING/PAST/CANCELLED is a total, disjoint cover; "
            + "endsAt==now lands only in UPCOMING; a COMPLETED booking with a future endsAt lands in PAST")
    void should_partitionBeTotalAndDisjoint_when_masterHasAllFiveStatusesAroundNow() throws Exception {
        String masterEmail = "mbpi-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID clientId = fixtures.createUser("mbpi-master-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID serviceId = fixtures.createIndependentMasterService(masterId);

        Fixture fx = seedEightRowFixture(clientId, masterId, serviceId, null);
        String token = fixtures.tokenFor(masterEmail);

        JsonNode unfiltered = call(token, null, null, null, null, null);
        assertThat(unfiltered.path("data").path("totalElements").asLong())
                .as("sanity: 8 rows seeded, unfiltered count must be 8")
                .isEqualTo(8);

        Set<UUID> upcoming = idSet(call(token, "UPCOMING", null, null, null, null));
        Set<UUID> past = idSet(call(token, "PAST", null, null, null, null));
        Set<UUID> cancelled = idSet(call(token, "CANCELLED", null, null, null, null));

        assertThat(upcoming)
                .as("UPCOMING = CONFIRMED not-yet-elapsed, including the endsAt==now boundary row")
                .containsExactlyInAnyOrder(fx.confirmedUpcoming, fx.confirmedBoundary);
        assertThat(past)
                .as("PAST = COMPLETED/NOT_COMPLETED (any endsAt) + elapsed unclosed CONFIRMED, "
                        + "including the Option-A hole (completedFutureEnds)")
                .containsExactlyInAnyOrder(fx.confirmedElapsed, fx.completedElapsed,
                        fx.completedFutureEnds, fx.notCompleted);
        assertThat(cancelled)
                .as("CANCELLED = CANCELLED/DECLINED regardless of endsAt")
                .containsExactlyInAnyOrder(fx.cancelled, fx.declined);

        assertThat(upcoming).doesNotContainAnyElementsOf(past);
        assertThat(upcoming).doesNotContainAnyElementsOf(cancelled);
        assertThat(past).doesNotContainAnyElementsOf(cancelled);
        assertThat(upcoming.size() + past.size() + cancelled.size())
                .as("total, disjoint cover: the three partitions sum to the unfiltered total")
                .isEqualTo(8);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Total-disjoint-cover — CLIENT path (structurally different query: single-query projection)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("CLIENT — partition UPCOMING/PAST/CANCELLED is a total, disjoint cover on the "
            + "client-projection code path too, scoped to the caller's own bookings only")
    void should_partitionBeTotalAndDisjoint_when_clientHasAllFiveStatusesAroundNow() throws Exception {
        String masterEmail = "mbpi-cli-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID serviceId = fixtures.createIndependentMasterService(masterId);
        String clientEmail = "mbpi-client-" + System.nanoTime() + "@beautica.test";
        UUID clientId = fixtures.createUser(clientEmail, "CLIENT", null);

        Fixture fx = seedEightRowFixture(clientId, masterId, serviceId, null);

        // Unrelated second client's own booking must never leak into the first client's page.
        UUID otherClientId = fixtures.createUser("mbpi-client-other-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        insertBooking(otherClientId, masterId, serviceId, null, "CANCELLED", NOW.plusHours(20), NOW.plusHours(21));

        String token = fixtures.tokenFor(clientEmail);

        Set<UUID> upcoming = idSet(call(token, "UPCOMING", null, null, null, null));
        Set<UUID> past = idSet(call(token, "PAST", null, null, null, null));
        Set<UUID> cancelled = idSet(call(token, "CANCELLED", null, null, null, null));

        assertThat(upcoming).containsExactlyInAnyOrder(fx.confirmedUpcoming, fx.confirmedBoundary);
        assertThat(past).containsExactlyInAnyOrder(fx.confirmedElapsed, fx.completedElapsed,
                fx.completedFutureEnds, fx.notCompleted);
        assertThat(cancelled)
                .as("must be exactly this client's own CANCELLED/DECLINED — never the other client's")
                .containsExactlyInAnyOrder(fx.cancelled, fx.declined);
        assertThat(upcoming.size() + past.size() + cancelled.size()).isEqualTo(8);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Total-disjoint-cover — SALON_OWNER path (lighter fixture: proves salon scope composes too)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("SALON_OWNER — one booking per partition, scoped to the owner's own salon only")
    void should_partitionBeTotalAndDisjoint_when_salonOwnerHasOneBookingPerPartition() throws Exception {
        BookingTestFixtures.SalonFixture salon = fixtures.createSalon("mbpi-owner-" + System.nanoTime() + "@beautica.test");
        UUID clientId = fixtures.createUser("mbpi-owner-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID serviceId = fixtures.createSalonService(salon.salonId(), salon.masterId());

        UUID upcomingId = insertBooking(clientId, salon.masterId(), serviceId, salon.salonId(),
                "CONFIRMED", NOW.plusHours(1), NOW.plusHours(2));
        UUID pastId = insertBooking(clientId, salon.masterId(), serviceId, salon.salonId(),
                "COMPLETED", NOW.minusHours(4), NOW.minusHours(3));
        UUID cancelledId = insertBooking(clientId, salon.masterId(), serviceId, salon.salonId(),
                "DECLINED", NOW.plusHours(6), NOW.plusHours(7));

        // Unrelated second owner's salon — must never leak into the first owner's page.
        BookingTestFixtures.SalonFixture otherSalon = fixtures.createSalon("mbpi-owner-b-" + System.nanoTime() + "@beautica.test");
        UUID otherServiceId = fixtures.createSalonService(otherSalon.salonId(), otherSalon.masterId());
        insertBooking(clientId, otherSalon.masterId(), otherServiceId, otherSalon.salonId(),
                "CONFIRMED", NOW.plusHours(1), NOW.plusHours(2));

        String token = fixtures.tokenFor(salon.ownerEmail());

        assertThat(idSet(call(token, "UPCOMING", null, null, null, null))).containsExactly(upcomingId);
        assertThat(idSet(call(token, "PAST", null, null, null, null))).containsExactly(pastId);
        assertThat(idSet(call(token, "CANCELLED", null, null, null, null))).containsExactly(cancelledId);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Status-alone independence — NOT_COMPLETED and CANCELLED/DECLINED partition assignment must
    // not depend on endsAt at all, on EITHER side of now (the eight-row fixture above only ever
    // seeds NOT_COMPLETED in the past and CANCELLED/DECLINED in the future; these two tests close
    // that asymmetry so the "regardless of endsAt" half of the contract is proven by an actual HTTP
    // round trip, not only by BookingSpecificationsTest's predicate-shape assertions).
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("a NOT_COMPLETED booking with a FUTURE endsAt still lands in PAST — the same "
            + "Option-A hole as COMPLETED, proven for the no-show status too")
    void should_includeInPast_when_notCompletedBookingHasFutureEndsAt() throws Exception {
        String masterEmail = "mbpi-notcompleted-future-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID clientId = fixtures.createUser("mbpi-notcompleted-future-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID serviceId = fixtures.createIndependentMasterService(masterId);

        UUID notCompletedFutureEnds = insertBooking(clientId, masterId, serviceId, null,
                "NOT_COMPLETED", NOW.minusHours(1), NOW.plusHours(5));

        String token = fixtures.tokenFor(masterEmail);

        assertThat(idSet(call(token, "PAST", null, null, null, null)))
                .as("NOT_COMPLETED must land in PAST unconditionally, even with endsAt in the future")
                .contains(notCompletedFutureEnds);
        assertThat(idSet(call(token, "UPCOMING", null, null, null, null)))
                .doesNotContain(notCompletedFutureEnds);
        assertThat(idSet(call(token, "CANCELLED", null, null, null, null)))
                .doesNotContain(notCompletedFutureEnds);
    }

    @Test
    @DisplayName("CANCELLED/DECLINED bookings with a PAST endsAt still land in CANCELLED, never PAST "
            + "— the CANCELLED bucket ignores endsAt on both sides of now, not only the future side "
            + "the eight-row fixture happens to exercise")
    void should_includeInCancelled_when_cancelledOrDeclinedBookingEndsAtIsInThePast() throws Exception {
        String masterEmail = "mbpi-cancelled-past-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID clientId = fixtures.createUser("mbpi-cancelled-past-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID serviceId = fixtures.createIndependentMasterService(masterId);

        UUID cancelledPast = insertBooking(clientId, masterId, serviceId, null,
                "CANCELLED", NOW.minusHours(10), NOW.minusHours(9));
        UUID declinedPast = insertBooking(clientId, masterId, serviceId, null,
                "DECLINED", NOW.minusHours(12), NOW.minusHours(11));

        String token = fixtures.tokenFor(masterEmail);

        assertThat(idSet(call(token, "CANCELLED", null, null, null, null)))
                .as("CANCELLED/DECLINED must land in CANCELLED regardless of endsAt, including when "
                        + "endsAt is well in the past")
                .containsExactlyInAnyOrder(cancelledPast, declinedPast);
        assertThat(idSet(call(token, "PAST", null, null, null, null)))
                .as("a past-endsAt CANCELLED/DECLINED row must NOT leak into PAST")
                .doesNotContain(cancelledPast, declinedPast);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Precedence — partition present makes status IGNORED, not a 400
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("?partition=CANCELLED&status=CONFIRMED — status is IGNORED, partition wins (200, "
            + "not 400), returning only CANCELLED/DECLINED rows")
    void should_ignoreStatusParam_when_partitionAlsoSupplied() throws Exception {
        String masterEmail = "mbpi-precedence-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID clientId = fixtures.createUser("mbpi-precedence-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID serviceId = fixtures.createIndependentMasterService(masterId);

        UUID confirmedId = insertBooking(clientId, masterId, serviceId, null, "CONFIRMED", NOW.plusHours(1), NOW.plusHours(2));
        UUID cancelledId = insertBooking(clientId, masterId, serviceId, null, "CANCELLED", NOW.plusHours(3), NOW.plusHours(4));

        JsonNode resp = call(fixtures.tokenFor(masterEmail), "CANCELLED", List.of("CONFIRMED"), null, null, null);

        assertThat(idList(resp))
                .as("partition=CANCELLED must win over status=CONFIRMED — never a 400, never the "
                        + "union/intersection of the two params")
                .containsExactly(cancelledId)
                .doesNotContain(confirmedId);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Composition — partition ANDs with from/to/serviceId
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("partition ANDs correctly with serviceId and from/to — never an OR, never ignored")
    void should_andWithServiceIdAndDateRange_when_partitionSupplied() throws Exception {
        String masterEmail = "mbpi-compose-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID clientId = fixtures.createUser("mbpi-compose-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID serviceA = fixtures.createIndependentMasterService(masterId);
        UUID serviceB = fixtures.createIndependentMasterService(masterId);

        // All four PAST-shaped (COMPLETED, elapsed), split across two services and two days.
        UUID inWindowServiceA = insertBookingForService(clientId, masterId, serviceA, "COMPLETED",
                NOW.minusDays(2).minusHours(2), NOW.minusDays(2).minusHours(1));
        UUID outOfWindowServiceA = insertBookingForService(clientId, masterId, serviceA, "COMPLETED",
                NOW.minusDays(10).minusHours(2), NOW.minusDays(10).minusHours(1));
        UUID inWindowServiceB = insertBookingForService(clientId, masterId, serviceB, "COMPLETED",
                NOW.minusDays(2).minusHours(4), NOW.minusDays(2).minusHours(3));
        // Also CANCELLED, in-window, service A — must be excluded by partition=PAST regardless of
        // being in the date/service window (proves the AND, not an OR across the three filters).
        insertBookingForService(clientId, masterId, serviceA, "CANCELLED",
                NOW.minusDays(2).minusHours(6), NOW.minusDays(2).minusHours(5));

        String fromDay = NOW.minusDays(3).toLocalDate().toString();
        String toDay = NOW.minusDays(1).toLocalDate().toString();

        JsonNode resp = call(fixtures.tokenFor(masterEmail), "PAST", null, fromDay, toDay, List.of(serviceA));

        assertThat(idList(resp))
                .as("partition=PAST AND from/to AND serviceId=A must return exactly the one row "
                        + "matching all three — not service B's match, not the out-of-window match, "
                        + "not the CANCELLED row")
                .containsExactly(inWindowServiceA)
                .doesNotContain(outOfWindowServiceA, inWindowServiceB);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Pagination — totalElements + cursor stay correct across a multi-page PAST result
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("partition=PAST spanning 2 pages — totalElements and each page match the ground "
            + "truth computed independently at test time, across rows alternating between the two "
            + "sub-clauses of the partition(PAST) OR (COMPLETED vs. elapsed-unclosed CONFIRMED); "
            + "all rows sit well before now, none straddle the now boundary itself")
    void should_keepTotalsAndCursorCorrect_when_partitionResultSpansMultiplePages() throws Exception {
        String masterEmail = "mbpi-page-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID clientId = fixtures.createUser("mbpi-page-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID serviceId = fixtures.createIndependentMasterService(masterId);

        int pastCount = 13;
        OffsetDateTime t = NOW.minusDays(30);
        for (int i = 0; i < pastCount; i++) {
            // Alternate COMPLETED (any endsAt) and elapsed-unclosed CONFIRMED — both PAST, but via
            // the two different sub-clauses of the partition(PAST) OR. This is the "straddle" this
            // test actually exercises: EVERY row here sits well before `now` (NOW.minusDays(30)
            // onward) — nothing straddles the now boundary itself; see the class javadoc's
            // "Pagination" bullet.
            if (i % 2 == 0) {
                insertBooking(clientId, masterId, serviceId, null, "COMPLETED", t, t.plusHours(1));
            } else {
                insertBooking(clientId, masterId, serviceId, null, "CONFIRMED", t, t.plusHours(1));
            }
            t = t.plusDays(1);
        }
        // Noise the ground truth must exclude: future CONFIRMED (UPCOMING) and CANCELLED.
        insertBooking(clientId, masterId, serviceId, null, "CONFIRMED", NOW.plusHours(1), NOW.plusHours(2));
        insertBooking(clientId, masterId, serviceId, null, "CANCELLED", NOW.plusHours(3), NOW.plusHours(4));

        List<UUID> groundTruth = jdbcTemplate.queryForList(
                "SELECT id FROM bookings WHERE master_id = ? AND "
                        + "(status IN ('COMPLETED','NOT_COMPLETED') OR (status = 'CONFIRMED' AND ends_at < ?)) "
                        + "ORDER BY starts_at DESC, id ASC",
                UUID.class, masterId, NOW);
        assertThat(groundTruth).as("fixture sanity check").hasSize(pastCount);

        String token = fixtures.tokenFor(masterEmail);
        int pageSize = 8;
        List<UUID> collected = new ArrayList<>();
        for (int page = 0; page < 2; page++) {
            JsonNode resp = callPaged(token, "PAST", page, pageSize);
            assertThat(resp.path("data").path("totalElements").asLong())
                    .as("page %d totalElements must reflect the PAST-filtered count only", page)
                    .isEqualTo(pastCount);

            List<UUID> pageIds = idList(resp);
            int from = page * pageSize;
            int to = Math.min(pastCount, from + pageSize);
            assertThat(pageIds)
                    .as("page %d must equal the ground-truth slice [%d,%d)", page, from, to)
                    .containsExactlyElementsOf(groundTruth.subList(from, to));
            collected.addAll(pageIds);
        }
        assertThat(collected)
                .as("both pages together reproduce the full ground truth, no row dropped or duplicated")
                .containsExactlyInAnyOrderElementsOf(groundTruth)
                .hasSize(pastCount);
        assertThat(new HashSet<>(collected)).hasSize(pastCount);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Invalid enum — 400, no echo of the valid constants
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("?partition=BOGUS returns 400 (not 500), and the body does not echo the valid "
            + "UPCOMING/PAST/CANCELLED constants")
    void should_return400WithoutEchoingEnumConstants_when_partitionParamIsUnrecognised() throws Exception {
        String clientEmail = "mbpi-badenum-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(clientEmail, "CLIENT", null);
        String token = fixtures.tokenFor(clientEmail);

        ResponseEntity<String> resp = restTemplate.exchange(
                BOOKINGS_URL + "/me?partition=BOGUS", HttpMethod.GET,
                new HttpEntity<>(fixtures.bearerHeaders(token)), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        String bodyLower = resp.getBody() == null ? "" : resp.getBody().toLowerCase();
        assertThat(bodyLower).doesNotContain("upcoming").doesNotContain("cancelled");
        // "past" is deliberately not asserted — too common an English substring elsewhere in a
        // generic error message to be a reliable leak signal.
    }

    // ── fixture record + seeding ────────────────────────────────────────────────────────────────

    private record Fixture(
            UUID confirmedUpcoming, UUID confirmedBoundary, UUID confirmedElapsed,
            UUID completedElapsed, UUID completedFutureEnds, UUID notCompleted,
            UUID cancelled, UUID declined) {
    }

    /**
     * Seeds one booking per {@link com.beautica.booking.enums.BookingStatus} value, plus a second
     * {@code CONFIRMED} row, spread on both sides of {@link #NOW} — see the class javadoc for the
     * expected partition assignment of each. Only the three {@code CONFIRMED} rows need
     * non-overlapping windows (the {@code no_overlapping_bookings} EXCLUDE constraint, V18/V113,
     * scopes to {@code status = 'CONFIRMED'} only); every other status may overlap freely.
     */
    private Fixture seedEightRowFixture(UUID clientId, UUID masterId, UUID masterServiceId, UUID salonId) {
        UUID confirmedUpcoming = insertBooking(clientId, masterId, masterServiceId, salonId,
                "CONFIRMED", NOW.plusHours(2), NOW.plusHours(3));
        UUID confirmedBoundary = insertBooking(clientId, masterId, masterServiceId, salonId,
                "CONFIRMED", NOW.minusHours(1), NOW);
        UUID confirmedElapsed = insertBooking(clientId, masterId, masterServiceId, salonId,
                "CONFIRMED", NOW.minusHours(3), NOW.minusHours(2));
        UUID completedElapsed = insertBooking(clientId, masterId, masterServiceId, salonId,
                "COMPLETED", NOW.minusHours(5), NOW.minusHours(4));
        // Option-A hole: BookingTemporalGuard.assertElapsedForComplete gates completion on
        // startsAt, not endsAt, so a COMPLETED booking's endsAt can legitimately be in the future.
        UUID completedFutureEnds = insertBooking(clientId, masterId, masterServiceId, salonId,
                "COMPLETED", NOW.minusHours(6), NOW.plusHours(5));
        UUID notCompleted = insertBooking(clientId, masterId, masterServiceId, salonId,
                "NOT_COMPLETED", NOW.minusHours(8), NOW.minusHours(7));
        UUID cancelled = insertBooking(clientId, masterId, masterServiceId, salonId,
                "CANCELLED", NOW.plusHours(10), NOW.plusHours(11));
        UUID declined = insertBooking(clientId, masterId, masterServiceId, salonId,
                "DECLINED", NOW.plusHours(12), NOW.plusHours(13));
        return new Fixture(confirmedUpcoming, confirmedBoundary, confirmedElapsed,
                completedElapsed, completedFutureEnds, notCompleted, cancelled, declined);
    }

    /** Inserts a booking row directly via SQL, bypassing the create/decline/complete flows. */
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

    private UUID insertBookingForService(UUID clientId, UUID masterId, UUID masterServiceId,
                                          String status, OffsetDateTime startsAt, OffsetDateTime endsAt) {
        return insertBooking(clientId, masterId, masterServiceId, null, status, startsAt, endsAt);
    }

    // ── HTTP helpers ─────────────────────────────────────────────────────────────────────────────

    private JsonNode call(String token, String partition, List<String> statuses,
                           String from, String to, List<UUID> serviceIds) throws Exception {
        List<String> parts = new ArrayList<>();
        if (partition != null) {
            parts.add("partition=" + partition);
        }
        if (statuses != null) {
            for (String s : statuses) {
                parts.add("status=" + s);
            }
        }
        if (from != null) {
            parts.add("from=" + from);
        }
        if (to != null) {
            parts.add("to=" + to);
        }
        if (serviceIds != null) {
            for (UUID id : serviceIds) {
                parts.add("serviceId=" + id);
            }
        }
        parts.add("size=50");
        String url = BOOKINGS_URL + "/me?" + String.join("&", parts);
        ResponseEntity<String> resp = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(fixtures.bearerHeaders(token)), String.class);
        assertThat(resp.getStatusCode()).as("request to %s must succeed", url).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(resp.getBody());
    }

    private JsonNode callPaged(String token, String partition, int page, int size) throws Exception {
        String url = BOOKINGS_URL + "/me?partition=" + partition + "&page=" + page + "&size=" + size;
        ResponseEntity<String> resp = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(fixtures.bearerHeaders(token)), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(resp.getBody());
    }

    private List<UUID> idList(JsonNode root) {
        return fixtures.extractIds(root);
    }

    private Set<UUID> idSet(JsonNode root) {
        return new HashSet<>(idList(root));
    }
}
