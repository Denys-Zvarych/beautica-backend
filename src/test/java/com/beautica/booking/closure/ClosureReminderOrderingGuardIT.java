package com.beautica.booking.closure;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.BookingTestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Audit-fix cycle-3 coverage gap — guards {@link ClosureReminderClaimService#fetchAggregates}
 * running BEFORE {@link ClosureReminderClaimService#claim} inside {@link
 * ClosureReminderClaimService#claimAndEnqueue}. The cycle-2 fix moved that call order (see both
 * methods' javadoc) because once {@link ClosureReminderRepository#findWindowAggregates} joined
 * {@code booking_closure_reminders} (the cycle-2 dedupe pre-filter), running it AFTER {@link
 * ClosureReminderRepository#claimDueReminders} in the SAME transaction made it read-committed
 * "read its own writes" — the claim statement's just-bumped {@code reminder_count} rows looked
 * newly dedupe-exhausted to the aggregate query, corrupting {@code excludedByDedupe} AND {@code
 * cappedByProviderLimit} (the latter via a shifted {@code ROW_NUMBER()} rank, not just a missed
 * row). The bug was caught once by {@code ClosureReminderGlobalCapIT} failing unexpectedly, but
 * NOTHING committed pins the ordering itself — every other fixture in this package either uses
 * fresh bookings (never reach the dedupe ceiling, {@code 0 -> 1}) or pre-seeded
 * already-exhausted ones (state predates the run, so intra-run ordering can't matter). This test
 * closes that gap: it seeds a booking at exactly {@code reminder_count == maxPerBooking - 1} for
 * a master with enough non-exhausted siblings to also exceed {@code maxPerProviderPerDay}, so a
 * SINGLE run's own claim flips that booking's dedupe eligibility mid-transaction — the only shape
 * that can distinguish correct from inverted ordering.
 *
 * <p><b>Worked arithmetic (see {@code should_reportPreClaimDedupeState_when_bookingCrossesTheDedupeCeilingWithinTheSameRun}
 * for the fixture) — master M has 4 CONFIRMED candidates in the window, oldest-to-newest: {@code
 * reminderedBooking} (already reminded once, {@code reminder_count=1}), then three fresh
 * siblings. {@code maxPerBooking=2}, {@code maxPerProviderPerDay=2}.</b>
 * <ul>
 *   <li>CORRECT order (aggregates read the PRE-claim ledger): all 4 candidates are dedupe-eligible
 *       ({@code reminderedBooking}'s {@code reminder_count=1 < 2}); ranked oldest-first, the
 *       provider cap of 2 admits {@code reminderedBooking} and the first sibling, capping the
 *       other two — {@code excludedByDedupe=0}, {@code cappedByProviderLimit=2}.</li>
 *   <li>INVERTED order (aggregates read the POST-claim ledger, after {@code claim} already bumped
 *       {@code reminderedBooking} to {@code reminder_count=2} and inserted a fresh row for the
 *       first sibling): {@code reminderedBooking} now looks permanently exhausted, moving it OUT
 *       of the eligible partition entirely and shrinking it to 3 rows — {@code excludedByDedupe=1},
 *       {@code cappedByProviderLimit=1} (the remaining rank shifts down by one, silently hiding an
 *       excess row).</li>
 * </ul>
 * The claim itself ({@code result.claimedIds()}) is identical either way (the claim statement's
 * own {@code ROW_NUMBER()} always ranks the PRE-claim state) — only the REPORTED aggregates
 * diverge, which is exactly why the existing dedupe/global-cap ITs (which assert on claimed ids
 * more than on the aggregate breakdown) never caught this.
 */
@TestPropertySource(properties = {
        "booking.closure-reminder.enabled=true",
        "booking.closure-reminder.dry-run=false"
})
@DisplayName("ClosureReminderOrderingGuardIT — fetchAggregates must read the ledger BEFORE claim mutates it (cycle-3 coverage-gap fix)")
class ClosureReminderOrderingGuardIT extends AbstractIntegrationTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2031-06-15T12:00:00Z");
    private static final int MAX_PER_BOOKING = 2;
    private static final int MAX_PER_PROVIDER_PER_DAY = 2;
    private static final int GENEROUS_GLOBAL_CAP = 100;

    @Autowired
    private ClosureReminderClaimService claimService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private BookingTestFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new BookingTestFixtures(null, jdbcTemplate, null, passwordEncoder);
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

    /** Seeds a {@code booking_closure_reminders} row at {@code reminder_count == maxPerBooking - 1}
     * — one reminder away from permanently exhausted, and (critically) still {@code
     * dedupe_eligible} under the PRE-claim ledger state this test exists to protect. {@code
     * last_sent_at} is set well before {@code dedupeCooldownBefore} (= {@code now - 1 day}) so the
     * claim statement's {@code ON CONFLICT ... WHERE} guard actually re-claims it this run — the
     * mid-transaction flip this test targets only happens if the claim SUCCEEDS. */
    private void markOneReminderAwayFromExhausted(UUID bookingId) {
        jdbcTemplate.update(
                "INSERT INTO booking_closure_reminders (booking_id, reminder_count, last_sent_at) "
                        + "VALUES (?, ?, ?)",
                bookingId, (short) (MAX_PER_BOOKING - 1), NOW.minusDays(2));
    }

    @Test
    @DisplayName("a booking reminded exactly (maxPerBooking - 1) times, claimed again in THIS run, "
            + "must be reported dedupe-eligible and counted toward cappedByProviderLimit — not "
            + "excludedByDedupe from a ledger state the run itself just produced")
    void should_reportPreClaimDedupeState_when_bookingCrossesTheDedupeCeilingWithinTheSameRun() {
        UUID clientId = fixtures.createUser(
                "closure-ordering-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID masterId = fixtures.createIndependentMaster(
                "closure-ordering-master-" + System.nanoTime() + "@beautica.test");
        UUID masterServiceId = fixtures.createIndependentMasterService(masterId);

        // The booking that will cross the dedupe ceiling DURING this run: oldest ends_at for its
        // master, already reminded once, reclaimable (last_sent_at well before the cooldown).
        OffsetDateTime base = NOW.minusDays(5);
        UUID reminderedBooking = insertConfirmedBooking(clientId, masterId, masterServiceId, base);
        markOneReminderAwayFromExhausted(reminderedBooking);

        // Three fresh, never-reminded siblings for the SAME master, all with LATER ends_at, so
        // total candidates for this master (4) exceeds maxPerProviderPerDay (2) regardless of
        // which of the four end up ranked inside the cap.
        UUID firstFreshSibling = insertConfirmedBooking(clientId, masterId, masterServiceId, base.plusHours(1));
        insertConfirmedBooking(clientId, masterId, masterServiceId, base.plusHours(2));
        insertConfirmedBooking(clientId, masterId, masterServiceId, base.plusHours(3));

        ClosureReminderWindow window = new ClosureReminderWindow(
                NOW, NOW.minus(Duration.ofDays(14)), NOW.minus(Duration.ofHours(2)), NOW.minusDays(1),
                MAX_PER_PROVIDER_PER_DAY, MAX_PER_BOOKING, GENEROUS_GLOBAL_CAP);

        ClosureReminderRunResult result = claimService.claimAndEnqueue(window);

        // The claim itself is unaffected by the ordering bug (claimDueReminders always ranks the
        // pre-claim state) — both reminderedBooking (bumped to reminder_count=2) and the first
        // fresh sibling (rn=1 and rn=2 respectively, oldest-first) are claimed; the other two
        // siblings are capped by the per-provider limit either way.
        assertThat(result.claimedIds())
                .as("the claim's own ROW_NUMBER() ranking is unaffected by the ordering bug — only "
                        + "the REPORTED aggregates diverge")
                .containsExactlyInAnyOrder(reminderedBooking, firstFreshSibling);

        assertThat(result.candidateCount())
                .as("raw candidate count is unaffected by ledger state either way")
                .isEqualTo(4L);
        assertThat(result.excludedByDedupe())
                .as("reminderedBooking was reminder_count=1 (< maxPerBooking=2) BEFORE this run's "
                        + "own claim ran — fetchAggregates must see that pre-claim state, so nothing "
                        + "is permanently dedupe-exhausted yet")
                .isZero();
        assertThat(result.cappedByProviderLimit())
                .as("all 4 candidates are dedupe-eligible pre-claim, so the per-provider cap of 2 "
                        + "correctly caps the 2 newest siblings — an inverted call order would move "
                        + "reminderedBooking into excludedByDedupe and shift this down to 1, silently "
                        + "hiding one over-the-cap row")
                .isEqualTo(2L);
    }
}
