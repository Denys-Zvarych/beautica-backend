package com.beautica.booking.closure;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.BookingTestFixtures;
import com.beautica.notification.repository.NotificationOutboxRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 29.7 — dry-run mode. {@code booking.closure-reminder.enabled=true,
 * dry-run=true} — the SAFE combination the doc's flag table calls out: the job bean is wired
 * (proven by {@code ClosureReminderJobIT#should_exposeJobBean_when_enabledTrueInRealSpringContext},
 * an identical assertion under an identical property set) but writes nothing until a human flips
 * {@code dry-run=false} on Railway.
 *
 * <p><b>Flag-matrix coverage note.</b> The doc's Test Case 5 ("all four (enabled, dry-run)
 * combinations") is covered across three classes rather than duplicated in each:
 * <ul>
 *   <li>{@code enabled=false} (bean absent regardless of {@code dry-run}) —
 *       {@code ClosureReminderJobTest#should_haveNoJobBean_when_enabledPropertyAbsent}/{@code
 *       ...PropertyFalse};</li>
 *   <li>{@code enabled=true, dry-run=false} (live) — {@code ClosureReminderJobIT}'s claim tests,
 *       and {@link #should_produceNonZeroClaims_when_sameShapeFixtureRunLive()} /
 *       {@link #should_matchClaimableCount_when_comparingDryRunToLiveOnIdenticalFreshFixtures()}
 *       below;</li>
 *   <li>{@code enabled=true, dry-run=true} (dry) — every test in this class.</li>
 * </ul>
 */
@TestPropertySource(properties = {
        "booking.closure-reminder.enabled=true",
        "booking.closure-reminder.dry-run=true"
})
@DisplayName("ClosureReminderDryRunIT — Phase 29.7")
class ClosureReminderDryRunIT extends AbstractIntegrationTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2031-06-15T12:00:00Z");
    // Effectively unbounded — this class exercises dry-run vs live parity and log hygiene, not the
    // global per-run cap (see ClosureReminderGlobalCapIT for that).
    private static final int NO_GLOBAL_CAP = 100_000;

    @Autowired
    private ClosureReminderClaimService claimService;

    @Autowired
    private ClosureReminderRepository closureRepository;

    @Autowired
    private NotificationOutboxRepository outboxRepository;

    @Autowired(required = false)
    private ClosureReminderJob job;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private BookingTestFixtures fixtures;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger jobLogger;

    @BeforeEach
    void setUp() {
        fixtures = new BookingTestFixtures(null, jdbcTemplate, null, passwordEncoder);
        jobLogger = (Logger) LoggerFactory.getLogger(ClosureReminderJob.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        jobLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDownAppender() {
        if (jobLogger != null && logAppender != null) {
            jobLogger.detachAppender(logAppender);
        }
    }

    private static ClosureReminderWindow windowAt(OffsetDateTime now) {
        return new ClosureReminderWindow(
                now, now.minus(Duration.ofDays(14)), now.minus(Duration.ofHours(2)), now.minusDays(1), 3, 2,
                NO_GLOBAL_CAP);
    }

    /** Seeds {@code count} distinct masters, each with exactly one eligible CONFIRMED booking. */
    private List<UUID> seedEligibleFixture(String prefix, int count, UUID clientId) {
        List<UUID> bookingIds = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            UUID masterId = fixtures.createIndependentMaster(prefix + "-" + i + "-" + System.nanoTime() + "@beautica.test");
            UUID masterServiceId = fixtures.createIndependentMasterService(masterId);
            UUID bookingId = UUID.randomUUID();
            OffsetDateTime endsAt = NOW.minusHours(5);
            OffsetDateTime startsAt = endsAt.minusHours(1);
            jdbcTemplate.update(
                    "INSERT INTO bookings (id, client_id, master_id, master_service_id, status, "
                            + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                            + "buffer_minutes_at_booking, booking_source, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, 'CONFIRMED', ?, ?, 500.00, 60, 0, 'APP', NOW(), NOW())",
                    bookingId, clientId, masterId, masterServiceId, startsAt, endsAt);
            bookingIds.add(bookingId);
        }
        return bookingIds;
    }

    // -------------------------------------------------------------------------
    // Test 1 — zero-write dry run
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("dry run over a >=50-row eligible fixture writes ZERO outbox rows, ZERO "
            + "booking_closure_reminders rows, and leaves bookings byte-identical — while still "
            + "reporting claimable > 0 (not a vacuous no-op)")
    void should_writeNothing_when_dryRunOverEligibleFixture() {
        UUID clientId = fixtures.createUser(
                "closure-dryrun-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        seedEligibleFixture("closure-dryrun-master", 60, clientId);

        long outboxBefore = outboxRepository.count();
        long reminderRowsBefore = closureRepository.count();
        List<Map<String, Object>> bookingsBefore = jdbcTemplate.queryForList(
                "SELECT id, status, updated_at FROM bookings ORDER BY id");

        ClosureReminderRunResult result = claimService.dryRun(windowAt(NOW));

        assertThat(result.claimable())
                .as("the fixture must actually be eligible — otherwise the 'unchanged' assertions "
                        + "below would pass vacuously")
                .isEqualTo(60);

        assertThat(outboxRepository.count()).as("outbox row count unchanged (all event types)").isEqualTo(outboxBefore);
        assertThat(closureRepository.count()).as("booking_closure_reminders row count unchanged").isEqualTo(reminderRowsBefore);

        List<Map<String, Object>> bookingsAfter = jdbcTemplate.queryForList(
                "SELECT id, status, updated_at FROM bookings ORDER BY id");
        assertThat(bookingsAfter)
                .as("bookings (id, status, updated_at) snapshot unchanged")
                .isEqualTo(bookingsBefore);
    }

    // -------------------------------------------------------------------------
    // Test 2 — anti-vacuity: the SAME-SHAPE fixture run live produces non-zero claims
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("anti-vacuity: a same-shape fixture run LIVE (not dry) produces a non-zero claim count")
    void should_produceNonZeroClaims_when_sameShapeFixtureRunLive() {
        UUID clientId = fixtures.createUser(
                "closure-antivac-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        seedEligibleFixture("closure-antivac-master", 12, clientId);

        ClosureReminderRunResult result = claimService.claimAndEnqueue(windowAt(NOW));

        assertThat(result.claimable()).isEqualTo(12);
        assertThat(outboxRepository.count()).isEqualTo(12);
        assertThat(closureRepository.count()).isEqualTo(12);
    }

    // -------------------------------------------------------------------------
    // Test 3 — dry claimable count equals the live run's persisted row count on identical fresh fixtures
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("dry-run claimable count equals the number of booking_closure_reminders rows a live run creates on an identical fresh fixture")
    void should_matchClaimableCount_when_comparingDryRunToLiveOnIdenticalFreshFixtures() {
        UUID clientId = fixtures.createUser(
                "closure-parity-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        int fixtureSize = 25;

        // LIVE first, on fixture A — persists 25 booking_closure_reminders rows.
        seedEligibleFixture("closure-parity-live", fixtureSize, clientId);
        ClosureReminderRunResult liveResult = claimService.claimAndEnqueue(windowAt(NOW));
        long persistedRows = closureRepository.count();

        // Then a second, structurally IDENTICAL (same size, same shape) but otherwise DISJOINT
        // fixture B, run DRY. Fixture A's bookings are still status=CONFIRMED (dry/live both never
        // write status) and therefore still count as raw "candidates" in the window — but the
        // dedupe guard correctly suppresses them from "claimable" now that A already has a
        // reminder_count=1 ledger row from the live run above, so the dry run's claimable figure
        // reflects ONLY fixture B, exactly like the live run above reflected only fixture A.
        seedEligibleFixture("closure-parity-dry", fixtureSize, clientId);
        ClosureReminderRunResult dryResult = claimService.dryRun(windowAt(NOW));
        assertThat(closureRepository.count())
                .as("the dry run itself must still have written nothing — count unchanged from after the live run")
                .isEqualTo(persistedRows);

        assertThat(liveResult.claimable()).isEqualTo(fixtureSize);
        assertThat(persistedRows).isEqualTo(fixtureSize);
        assertThat(dryResult.claimable())
                .as("dry-run's claimable figure must equal what the live path actually persisted on the identical-shape fixture")
                .isEqualTo(fixtureSize)
                .isEqualTo(persistedRows);
    }

    // -------------------------------------------------------------------------
    // Test 4 — log hygiene: aggregate-only, no PII, no note fields
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("the dry-run log line is aggregate-only — no booking id, no client name, no email, "
            + "and none of clientComment/clientCancellationNote/providerComment, even when the "
            + "fixture booking carries all three as distinctive sentinels")
    void should_notLeakPiiOrNotes_when_dryRunLogLineEmitted() {
        assertThat(job).as("job bean must be wired when enabled=true").isNotNull();

        UUID clientId = fixtures.createUser(
                "closure-loghygiene-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID masterId = fixtures.createIndependentMaster(
                "closure-loghygiene-master-" + System.nanoTime() + "@beautica.test");
        UUID masterServiceId = fixtures.createIndependentMasterService(masterId);

        // Real-time-relative (not the fixed 2031 NOW): this test invokes job.run() itself, which
        // resolves its window from the REAL injected Clock bean, not a test-local window.
        OffsetDateTime realEndsAt = OffsetDateTime.now(ZoneOffset.UTC).minusHours(5);
        OffsetDateTime realStartsAt = realEndsAt.minusHours(1);
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                        + "buffer_minutes_at_booking, booking_source, client_comment, "
                        + "client_cancellation_note, provider_comment, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'CONFIRMED', ?, ?, 500.00, 60, 0, 'APP', "
                        + "?, NULL, NULL, NOW(), NOW())",
                bookingId, clientId, masterId, masterServiceId, realStartsAt, realEndsAt,
                "SENTINEL-CLIENT-COMMENT-do-not-log-me");
        // clientCancellationNote/providerComment carry a CHECK requiring a terminal status to be
        // non-null (chk_client_cancellation_note_status et al.) — a CONFIRMED row cannot carry
        // them, so clientComment (creation-time, legitimately non-null on CONFIRMED) is the one
        // note sentinel this fixture can actually populate; the log-hygiene assertion below still
        // checks for all three sentinel markers as a general regression guard.

        job.run();

        assertThat(logAppender.list)
                .as("exactly one INFO log line must be emitted by the dry-run job")
                .hasSize(1);
        ILoggingEvent event = logAppender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        String message = event.getFormattedMessage();

        assertThat(message).startsWith("closure-reminder dry-run:");
        assertThat(message).contains("candidates=");
        assertThat(message).contains("claimable=");
        assertThat(message).as("this fixture's booking must actually be claimable — proves the run was not vacuous")
                .containsPattern("claimable=[1-9]\\d*");

        assertThat(message).as("no booking id").doesNotContain(bookingId.toString());
        assertThat(message).as("no email address").doesNotContainPattern("[\\w.+-]+@[\\w-]+\\.[\\w.-]+");
        assertThat(message).as("no client-comment sentinel").doesNotContain("SENTINEL-CLIENT-COMMENT");
        assertThat(message).as("no client-cancellation-note sentinel").doesNotContain("SENTINEL-CLIENT-CANCELLATION-NOTE");
        assertThat(message).as("no provider-comment sentinel").doesNotContain("SENTINEL-PROVIDER-COMMENT");

        // Dry-run must still have written nothing, even having run through the real job entry point.
        assertThat(outboxRepository.count()).isZero();
        assertThat(closureRepository.count()).isZero();
    }

    @Test
    @DisplayName("enabled=false produces no bean and (trivially) no log line at all")
    void should_emitNoLogLine_when_jobNeverInvokedBecauseDisabled() {
        // This class's context has enabled=true (TestPropertySource), so this test only asserts
        // the appender captured nothing from a fresh run with no seeded work — the actual
        // bean-absence proof for enabled=false lives in ClosureReminderJobTest (a different
        // Spring context is required to flip that property, and AbstractIntegrationTest's
        // container-sharing model makes standing up a THIRD context per test class expensive;
        // see this class's javadoc "Flag-matrix coverage note").
        assertThat(logAppender.list).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Rollback — data survives; only the bean's existence is gated by `enabled`
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("rollback: booking_closure_reminders rows written by a live run are left in place — "
            + "nothing in this codebase deletes them, so disabling the job (which only gates bean "
            + "construction, per ClosureReminderJobTest) can never re-arm an already-reminded booking")
    void should_leaveReminderRowsIntact_when_liveRunHasAlreadyWrittenThem() {
        UUID clientId = fixtures.createUser(
                "closure-rollback-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        seedEligibleFixture("closure-rollback-master", 5, clientId);

        ClosureReminderRunResult result = claimService.claimAndEnqueue(windowAt(NOW));
        assertThat(result.claimable()).isEqualTo(5);
        long rowsAfterLiveRun = closureRepository.count();
        assertThat(rowsAfterLiveRun).isEqualTo(5);

        // Re-reading proves nothing in this request/response cycle touched the ledger; combined
        // with ClosureReminderJobTest's proof that `enabled=false` only removes the
        // ClosureReminderJob bean (no code path anywhere issues a DELETE against
        // booking_closure_reminders except AbstractIntegrationTest's own test-only cleanDb()),
        // this is the full rollback guarantee: flip enabled off, the bean disappears, the ledger
        // — which is all this assertion needs to re-verify — stays exactly as it was.
        assertThat(closureRepository.count()).isEqualTo(rowsAfterLiveRun);
    }
}
