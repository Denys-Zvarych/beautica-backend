package com.beautica.booking.closure;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.BookingTestFixtures;
import com.beautica.booking.domain.BookingClosureRule;
import com.beautica.booking.entity.Booking;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 29.6 — real Postgres (Testcontainers) integration tests for {@link
 * ClosureReminderClaimService}'s atomic native claim. Every test constructs its own explicit
 * {@link ClosureReminderWindow} rather than relying on the injected {@code Clock} bean advancing —
 * this is simpler and equally faithful to production behaviour, since {@link ClosureReminderJob}'s
 * ENTIRE job is "resolve a window from the Clock, then hand it to this exact service" (already
 * covered in isolation by {@code ClosureReminderJobTest#resolveWindow}). {@code
 * booking.closure-reminder.enabled=true} is set via {@code @TestPropertySource} so the job bean
 * itself is also provably wired in a real Spring context (see the first test below).
 */
@TestPropertySource(properties = {
        "booking.closure-reminder.enabled=true",
        "booking.closure-reminder.dry-run=false"
})
@DisplayName("ClosureReminderJob — integration (Phase 29.6)")
class ClosureReminderJobIT extends AbstractIntegrationTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2031-06-15T12:00:00Z");
    private static final Duration LOOKBACK_MAX = Duration.ofDays(14);
    private static final Duration LOOKBACK_MIN = Duration.ofHours(2);
    private static final int MAX_PER_PROVIDER_PER_DAY = 3;
    private static final int MAX_PER_BOOKING = 2;
    // Effectively unbounded — this class exercises the per-provider cap, dedupe, and concurrency
    // controls, not the global per-run cap (see ClosureReminderGlobalCapIT for that).
    private static final int NO_GLOBAL_CAP = 100_000;

    @Autowired
    private ClosureReminderClaimService claimService;

    @Autowired
    private ClosureReminderRepository closureRepository;

    @Autowired(required = false)
    private ClosureReminderJob job;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private EntityManager entityManager;

    private BookingTestFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new BookingTestFixtures(null, jdbcTemplate, null, passwordEncoder);
    }

    private static ClosureReminderWindow windowAt(OffsetDateTime now) {
        return new ClosureReminderWindow(
                now, now.minus(LOOKBACK_MAX), now.minus(LOOKBACK_MIN), now.minusDays(1),
                MAX_PER_PROVIDER_PER_DAY, MAX_PER_BOOKING, NO_GLOBAL_CAP);
    }

    private UUID insertConfirmedBooking(UUID clientId, UUID masterId, UUID masterServiceId, OffsetDateTime endsAt) {
        UUID bookingId = UUID.randomUUID();
        OffsetDateTime startsAt = endsAt.minusHours(1);
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                        + "buffer_minutes_at_booking, booking_source, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'CONFIRMED', ?, ?, 500.00, 60, 0, 'APP', NOW(), NOW())",
                bookingId, clientId, masterId, masterServiceId, startsAt, endsAt);
        return bookingId;
    }

    // -------------------------------------------------------------------------
    // Real Spring context — the job bean is wired when enabled=true
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("job bean is present in a real Spring context when booking.closure-reminder.enabled=true")
    void should_exposeJobBean_when_enabledTrueInRealSpringContext() {
        assertThat(job).isNotNull();
    }

    // -------------------------------------------------------------------------
    // Control 1 — bounded lookback window
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("window boundaries: exactly the middle three of five endsAt positions are claimed")
    void should_claimOnlyMiddleThree_when_windowBoundariesTested() {
        UUID clientId = fixtures.createUser("closure-window-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);

        OffsetDateTime[] positions = {
                NOW.minusHours(1),                       // too recent — inside the 2h floor
                NOW.minus(LOOKBACK_MIN),                  // exactly on the floor — claimed
                NOW.minus(LOOKBACK_MIN).minusSeconds(1),  // just past the floor — claimed
                NOW.minus(LOOKBACK_MAX),                  // exactly on the ceiling — claimed
                NOW.minus(LOOKBACK_MAX).minusSeconds(1),  // just past the ceiling — too old
        };
        Map<UUID, Integer> bookingToPosition = new java.util.HashMap<>();
        for (int i = 0; i < positions.length; i++) {
            UUID masterId = fixtures.createIndependentMaster(
                    "closure-window-master-" + i + "-" + System.nanoTime() + "@beautica.test");
            UUID masterServiceId = fixtures.createIndependentMasterService(masterId);
            UUID bookingId = insertConfirmedBooking(clientId, masterId, masterServiceId, positions[i]);
            bookingToPosition.put(bookingId, i);
        }

        ClosureReminderRunResult result = claimService.claimAndEnqueue(windowAt(NOW));

        assertThat(result.claimedIds()).hasSize(3);
        Set<Integer> claimedPositions = result.claimedIds().stream()
                .map(bookingToPosition::get).collect(Collectors.toSet());
        assertThat(claimedPositions).containsExactlyInAnyOrder(1, 2, 3);
    }

    // -------------------------------------------------------------------------
    // Control 2 — per-provider daily cap, deterministic oldest-first
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("per-provider cap: one master with 40 eligible bookings yields exactly 3 claims — the 3 OLDEST by endsAt")
    void should_claimOnlyOldestThree_when_oneMasterHas40EligibleBookings() {
        UUID clientId = fixtures.createUser("closure-cap-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID masterId = fixtures.createIndependentMaster("closure-cap-master-" + System.nanoTime() + "@beautica.test");
        UUID masterServiceId = fixtures.createIndependentMasterService(masterId);

        // 40 non-overlapping 1h bookings, 2h apart, all comfortably inside the [now-14d, now-2h]
        // window — spans ~3.3 days starting 13 days back.
        List<UUID> orderedByEndsAt = new ArrayList<>();
        OffsetDateTime base = NOW.minusDays(13);
        for (int i = 0; i < 40; i++) {
            OffsetDateTime endsAt = base.plusHours(2L * i);
            orderedByEndsAt.add(insertConfirmedBooking(clientId, masterId, masterServiceId, endsAt));
        }

        ClosureReminderRunResult result = claimService.claimAndEnqueue(windowAt(NOW));

        assertThat(result.claimedIds()).hasSize(3);
        assertThat(result.claimedIds())
                .as("the cap must select the 3 OLDEST eligible bookings, deterministically")
                .containsExactlyInAnyOrderElementsOf(orderedByEndsAt.subList(0, 3));
    }

    // -------------------------------------------------------------------------
    // Control 3 — dedupe: at most N reminders EVER per booking
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("dedupe over time: running 10 times, advancing 'now' a day between runs, yields exactly 2 reminders total; runs 3-10 claim nothing")
    void should_capAtTwoReminders_when_runningTenTimesAcrossDays() {
        UUID clientId = fixtures.createUser("closure-dedupe-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID masterId = fixtures.createIndependentMaster("closure-dedupe-master-" + System.nanoTime() + "@beautica.test");
        UUID masterServiceId = fixtures.createIndependentMasterService(masterId);
        // Fixed a few hours before NOW — stays inside every one of the 10 daily-advancing windows.
        UUID bookingId = insertConfirmedBooking(clientId, masterId, masterServiceId, NOW.minusHours(5));

        int totalClaims = 0;
        for (int run = 0; run < 10; run++) {
            // Strictly MORE than 24h apart (+1 extra minute per run) — the dedupe guard is
            // `last_sent_at < now - 1 day` (strict), so advancing by EXACTLY 1 day would make
            // `now - 1 day` land exactly ON the previous run's last_sent_at and fail the strict
            // comparison. A real daily cron never fires at an exact 24h boundary either.
            OffsetDateTime runNow = NOW.plusDays(run).plusMinutes(run);
            ClosureReminderRunResult result = claimService.claimAndEnqueue(windowAt(runNow));
            if (run < 2) {
                assertThat(result.claimedIds())
                        .as("run %d must claim the booking", run)
                        .containsExactly(bookingId);
                totalClaims++;
            } else {
                assertThat(result.claimedIds())
                        .as("run %d must claim nothing — dedupe ceiling already reached", run)
                        .isEmpty();
            }
        }

        assertThat(totalClaims).isEqualTo(2);
        BookingClosureReminder row = closureRepository.findById(bookingId).orElseThrow();
        assertThat(row.getReminderCount()).isEqualTo((short) 2);
    }

    @Test
    @DisplayName("same-day re-run: running twice within the same simulated day claims nothing the second time")
    void should_claimNothing_when_sameDayRerun() {
        UUID clientId = fixtures.createUser("closure-sameday-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID masterId = fixtures.createIndependentMaster("closure-sameday-master-" + System.nanoTime() + "@beautica.test");
        UUID masterServiceId = fixtures.createIndependentMasterService(masterId);
        UUID bookingId = insertConfirmedBooking(clientId, masterId, masterServiceId, NOW.minusHours(5));

        ClosureReminderRunResult first = claimService.claimAndEnqueue(windowAt(NOW));
        assertThat(first.claimedIds()).containsExactly(bookingId);

        ClosureReminderRunResult second = claimService.claimAndEnqueue(windowAt(NOW.plusHours(3)));
        assertThat(second.claimedIds()).as("same-day re-run must be a no-op").isEmpty();

        BookingClosureReminder row = closureRepository.findById(bookingId).orElseThrow();
        assertThat(row.getReminderCount()).isEqualTo((short) 1);
    }

    // -------------------------------------------------------------------------
    // Control 3b — dedupe pre-filter <-> ON CONFLICT guard equivalence (cycle-2 HIGH fix)
    // -------------------------------------------------------------------------

    /**
     * backend-perf HIGH fix (audit-fix cycle 2) — {@link ClosureReminderRepository#claimDueReminders}
     * now expresses the {@code reminder_count >= maxPerBooking} half of the dedupe predicate TWICE:
     * once as a pre-filter inside the ranked subquery (excluding PERMANENTLY-unclaimable rows
     * BEFORE {@code ROW_NUMBER()}/the global {@code LIMIT} run), and once as (one half of) the
     * pre-existing {@code ON CONFLICT ... WHERE} guard, which ALSO keeps the cooldown half. Two
     * expressions of the same half-rule can drift; this test fences that the same way {@code
     * should_agreeWithCanonicalRule_when_comparingNativeClaimCandidatesToBookingClosureRule} fences
     * the native-claim/{@code BookingClosureRule} duplication above.
     *
     * <p><b>The pre-filter is deliberately NOT the full {@code ON CONFLICT} predicate</b> — see
     * {@link ClosureReminderRepository#claimDueReminders}'s javadoc for why an earlier version of
     * this fix that used the FULL predicate (reminder-count AND cooldown) broke {@code
     * should_neverDoubleClaim_when_twoThreadsRaceConcurrently} above (Control 4): excluding
     * cooldown-only rows from ranking let a second overlapping run re-rank a master's remaining
     * pool and claim MORE than the per-provider cap. So this test proves TWO separate properties
     * instead of one blanket equality:
     * <ol>
     *   <li><b>Precision</b> — {@link ClosureReminderRepository#findDedupeEligibleCandidateIds}
     *       (the pre-filter predicate, standalone) admits a row if and only if {@code
     *       reminder_count < maxPerBooking} (or no ledger row exists) — regardless of cooldown.</li>
     *   <li><b>Soundness</b> — a real claim over the SAME fixture, with generous per-provider/global
     *       caps so nothing else can suppress a row, claims EXACTLY the never-reminded and
     *       past-cooldown-with-budget rows, and (critically) does NOT claim the still-cooling-down
     *       row even though the pre-filter admitted it into ranking — proving the {@code ON
     *       CONFLICT ... WHERE} guard's cooldown half still does its job downstream of the
     *       pre-filter, exactly as it did before this fix.</li>
     * </ol>
     */
    @Test
    @DisplayName("dedupe pre-filter precision + soundness: findDedupeEligibleCandidateIds admits "
            + "exactly the non-exhausted rows, and a real claim still respects the cooldown guard "
            + "for a non-exhausted-but-cooling-down row")
    void should_admitExactlyNonExhaustedRows_when_comparingDedupePreFilterToReminderCountCeiling() {
        UUID clientId = fixtures.createUser("closure-dedupeequiv-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        OffsetDateTime endsAt = NOW.minusDays(5);
        OffsetDateTime dedupeCooldownBefore = NOW.minusDays(1);

        // never reminded — no booking_closure_reminders row at all -> non-exhausted
        UUID neverReminded = seedDedupeFixtureRow(clientId, "never", endsAt, null, null);
        // reminder_count < max AND last_sent_at before the cooldown floor -> non-exhausted, claimable
        UUID eligibleAfterCooldown = seedDedupeFixtureRow(clientId, "eligible-cooldown", endsAt, 1, NOW.minusDays(2));
        // reminder_count < max but last_sent_at still inside the cooldown window -> non-exhausted,
        // admitted into ranking by the pre-filter, but must still be suppressed by the ON CONFLICT
        // guard's cooldown check.
        UUID stillCoolingDown = seedDedupeFixtureRow(clientId, "cooling-down", endsAt, 1, NOW.minusHours(1));
        // reminder_count == max -> PERMANENTLY exhausted, excluded by the pre-filter regardless of cooldown
        UUID exhaustedPastCooldown = seedDedupeFixtureRow(clientId, "exhausted-past-cooldown", endsAt, MAX_PER_BOOKING, NOW.minusDays(2));
        UUID exhaustedAndCoolingDown = seedDedupeFixtureRow(clientId, "exhausted-cooling-down", endsAt, MAX_PER_BOOKING, NOW.minusHours(1));

        Set<UUID> seededIds = Set.of(
                neverReminded, eligibleAfterCooldown, stillCoolingDown, exhaustedPastCooldown, exhaustedAndCoolingDown);

        OffsetDateTime windowStart = NOW.minus(LOOKBACK_MAX);
        OffsetDateTime windowEnd = NOW.minus(LOOKBACK_MIN);

        // ── Property 1: precision — the pre-filter's own predicate, standalone ────────────────
        Set<UUID> preFilterIds = new HashSet<>(closureRepository.findDedupeEligibleCandidateIds(
                windowStart, windowEnd, MAX_PER_BOOKING));
        preFilterIds.retainAll(seededIds);

        assertThat(preFilterIds)
                .as("the pre-filter must admit every row with reminder_count < maxPerBooking "
                        + "regardless of cooldown state, and exclude only the permanently-exhausted rows")
                .containsExactlyInAnyOrder(neverReminded, eligibleAfterCooldown, stillCoolingDown);

        // ── Property 2: soundness — the real claim, generous caps so only dedupe can suppress ──
        ClosureReminderWindow window = new ClosureReminderWindow(
                NOW, windowStart, windowEnd, dedupeCooldownBefore, 10, MAX_PER_BOOKING, 100_000);
        ClosureReminderRunResult result = claimService.claimAndEnqueue(window);
        Set<UUID> actuallyClaimed = new HashSet<>(result.claimedIds());
        actuallyClaimed.retainAll(seededIds);

        assertThat(actuallyClaimed)
                .as("the real claim must claim exactly the never-reminded and past-cooldown rows — "
                        + "the still-cooling-down row was admitted into RANKING by the pre-filter "
                        + "but must still be rejected by the ON CONFLICT ... WHERE guard's cooldown "
                        + "check, and the exhausted rows were never even ranked")
                .containsExactlyInAnyOrder(neverReminded, eligibleAfterCooldown)
                .isSubsetOf(preFilterIds);
    }

    /** Seeds one CONFIRMED booking and, if {@code reminderCount} is non-null, a matching {@code
     * booking_closure_reminders} row — used by the dedupe pre-filter/ON-CONFLICT-guard equivalence
     * test above to cover every ledger state on its own dedicated master. */
    private UUID seedDedupeFixtureRow(UUID clientId, String label, OffsetDateTime endsAt, Integer reminderCount, OffsetDateTime lastSentAt) {
        UUID masterId = fixtures.createIndependentMaster(
                "closure-dedupeequiv-" + label + "-" + System.nanoTime() + "@beautica.test");
        UUID masterServiceId = fixtures.createIndependentMasterService(masterId);
        UUID bookingId = insertConfirmedBooking(clientId, masterId, masterServiceId, endsAt);
        if (reminderCount != null) {
            jdbcTemplate.update(
                    "INSERT INTO booking_closure_reminders (booking_id, reminder_count, last_sent_at) VALUES (?, ?, ?)",
                    bookingId, reminderCount.shortValue(), lastSentAt);
        }
        return bookingId;
    }

    // -------------------------------------------------------------------------
    // Control 4 — concurrency-safe claim
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("concurrency: two threads racing the claim simultaneously never claim the same booking twice, across 20 iterations")
    void should_neverDoubleClaim_when_twoThreadsRaceConcurrently() throws Exception {
        UUID clientId = fixtures.createUser("closure-race-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int iteration = 0; iteration < 20; iteration++) {
                UUID masterId = fixtures.createIndependentMaster(
                        "closure-race-master-" + iteration + "-" + System.nanoTime() + "@beautica.test");
                UUID masterServiceId = fixtures.createIndependentMasterService(masterId);
                List<UUID> eligible = new ArrayList<>();
                for (int i = 0; i < 5; i++) {
                    eligible.add(insertConfirmedBooking(clientId, masterId, masterServiceId, NOW.minusDays(1).plusHours(i)));
                }

                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch go = new CountDownLatch(1);
                ClosureReminderWindow window = windowAt(NOW);

                Future<List<UUID>> f1 = pool.submit(() -> raceClaim(ready, go, window));
                Future<List<UUID>> f2 = pool.submit(() -> raceClaim(ready, go, window));

                assertThat(ready.await(10, TimeUnit.SECONDS)).as("both threads must reach the start line").isTrue();
                go.countDown();

                List<UUID> claimed1 = f1.get(30, TimeUnit.SECONDS);
                List<UUID> claimed2 = f2.get(30, TimeUnit.SECONDS);

                Set<UUID> unionClaimed = new HashSet<>(claimed1);
                unionClaimed.addAll(claimed2);

                assertThat(claimed1.size() + claimed2.size())
                        .as("iteration %d: no booking may be claimed by both threads", iteration)
                        .isEqualTo(unionClaimed.size());
                assertThat(unionClaimed)
                        .as("iteration %d: total distinct claims must equal the single-threaded cap (3)", iteration)
                        .hasSize(3);

                long persistedRows = closureRepository.findAllById(eligible).size();
                assertThat(persistedRows)
                        .as("iteration %d: exactly 3 booking_closure_reminders rows must exist for this master's fixture", iteration)
                        .isEqualTo(3);
                closureRepository.findAllById(eligible)
                        .forEach(row -> assertThat(row.getReminderCount()).isEqualTo((short) 1));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private List<UUID> raceClaim(CountDownLatch ready, CountDownLatch go, ClosureReminderWindow window) throws InterruptedException {
        ready.countDown();
        go.await(10, TimeUnit.SECONDS);
        return claimService.claimAndEnqueue(window).claimedIds();
    }

    // -------------------------------------------------------------------------
    // Zero status mutation — the small-fixture version of the headline gate
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("zero status mutation: (id, status, updated_at) snapshot is identical before and after a live run")
    void should_leaveBookingsUntouched_when_liveRunClaims() {
        UUID clientId = fixtures.createUser("closure-nomutate-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        List<UUID> bookingIds = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            UUID masterId = fixtures.createIndependentMaster(
                    "closure-nomutate-master-" + i + "-" + System.nanoTime() + "@beautica.test");
            UUID masterServiceId = fixtures.createIndependentMasterService(masterId);
            bookingIds.add(insertConfirmedBooking(clientId, masterId, masterServiceId, NOW.minusDays(1)));
        }

        List<Map<String, Object>> before = jdbcTemplate.queryForList(
                "SELECT id, status, updated_at FROM bookings ORDER BY id");

        ClosureReminderRunResult result = claimService.claimAndEnqueue(windowAt(NOW));
        assertThat(result.claimedIds()).hasSize(10);

        List<Map<String, Object>> after = jdbcTemplate.queryForList(
                "SELECT id, status, updated_at FROM bookings ORDER BY id");

        assertThat(after)
                .as("a live claim+enqueue run must never write bookings.status or touch the row at all")
                .isEqualTo(before);
    }

    // -------------------------------------------------------------------------
    // Native-claim <-> canonical-rule equivalence (fences the one place the rule is expressed twice)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("native claim candidates (ignoring caps/dedupe) exactly equal BookingClosureRule.awaitingClosure(now) intersected with the lookback window, on a shared 30-row fixture")
    void should_agreeWithCanonicalRule_when_comparingNativeClaimCandidatesToBookingClosureRule() {
        UUID clientId = fixtures.createUser("closure-equiv-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);

        Set<UUID> seededIds = new HashSet<>();
        // 5 statuses x 6 endsAt positions (2 inside the window, 4 outside/boundary) = 30 rows.
        OffsetDateTime[] positions = {
                NOW.minusHours(1),                        // too recent
                NOW.minus(LOOKBACK_MIN),                   // in window (boundary)
                NOW.minusDays(7),                          // in window (middle)
                NOW.minus(LOOKBACK_MAX),                   // in window (boundary)
                NOW.minus(LOOKBACK_MAX).minusSeconds(1),   // too old
                NOW.plusHours(1),                          // future — not elapsed at all
        };
        for (com.beautica.booking.enums.BookingStatus status : com.beautica.booking.enums.BookingStatus.values()) {
            for (OffsetDateTime endsAt : positions) {
                UUID masterId = fixtures.createIndependentMaster(
                        "closure-equiv-master-" + status + "-" + endsAt.toEpochSecond() + "-" + System.nanoTime() + "@beautica.test");
                UUID masterServiceId = fixtures.createIndependentMasterService(masterId);
                UUID bookingId = UUID.randomUUID();
                OffsetDateTime startsAt = endsAt.minusHours(1);
                jdbcTemplate.update(
                        "INSERT INTO bookings (id, client_id, master_id, master_service_id, status, "
                                + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                                + "buffer_minutes_at_booking, booking_source, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, 500.00, 60, 0, 'APP', NOW(), NOW())",
                        bookingId, clientId, masterId, masterServiceId, status.name(), startsAt, endsAt);
                seededIds.add(bookingId);
            }
        }
        assertThat(seededIds).hasSize(30);

        OffsetDateTime windowStart = NOW.minus(LOOKBACK_MAX);
        OffsetDateTime windowEnd = NOW.minus(LOOKBACK_MIN);

        // ── native side — ClosureReminderRepository#findCandidateIds ──────────────────────────
        Set<UUID> nativeIds = new HashSet<>(closureRepository.findCandidateIds(windowStart, windowEnd));
        nativeIds.retainAll(seededIds);

        // ── canonical side — BookingClosureRule.awaitingClosure(NOW), intersected with the SAME
        //    window, as a real Criteria query (mirrors BookingClosureRuleEquivalenceIT) ─────────
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<UUID> idQuery = cb.createQuery(UUID.class);
        Root<Booking> root = idQuery.from(Booking.class);
        Predicate idInSeeded = root.get("id").in(seededIds);
        Predicate closurePredicate = BookingClosureRule.awaitingClosure(NOW).toPredicate(root, idQuery, cb);
        Predicate windowPredicate = cb.and(
                cb.greaterThanOrEqualTo(root.get("endsAt"), windowStart),
                cb.lessThanOrEqualTo(root.get("endsAt"), windowEnd));
        idQuery.select(root.get("id")).where(cb.and(idInSeeded, closurePredicate, windowPredicate));
        Set<UUID> canonicalIds = new HashSet<>(entityManager.createQuery(idQuery).getResultList());

        assertThat(nativeIds)
                .as("the native claim's candidate predicate must select EXACTLY the same id set as "
                        + "BookingClosureRule.awaitingClosure(now) intersected with the lookback window")
                .isEqualTo(canonicalIds)
                .hasSize(3);
    }
}
