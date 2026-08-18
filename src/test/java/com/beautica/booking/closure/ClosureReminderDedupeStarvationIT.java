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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * backend-perf HIGH fix (audit-fix cycle 2) — reproduces the exact scenario backend-perf found
 * against the live tree: the cycle-1 global-cap {@code LIMIT} in {@link
 * ClosureReminderRepository#claimDueReminders} sat BEFORE the {@code ON CONFLICT ... WHERE} dedupe
 * guard. {@code ends_at} never changes for a booking that stays {@code CONFIRMED}, so a booking
 * already at {@code reminder_count >= maxPerBooking} is PERMANENTLY un-claimable, yet it keeps
 * winning the "globally oldest" race in {@code ORDER BY c.ends_at ASC LIMIT
 * :maxTotalClaimsPerRun} on every future run. Once enough permanently-stuck bookings occupy the
 * globally-oldest positions to fill {@code maxTotalClaimsPerRun} on their own, the run's {@code
 * INSERT ... RETURNING} returns ZERO rows — silently, with no exception and no log signature
 * distinguishing it from an ordinary quiet day — even with thousands of legitimate,
 * never-reminded candidates sitting just past the cutoff.
 *
 * <p>This class seeds exactly that shape: {@link #EXHAUSTED_COUNT} bookings, already at {@code
 * reminder_count == maxPerBooking} (dedupe-exhausted), holding the globally-oldest {@code ends_at}
 * positions in the window — one per master, so the per-provider cap cannot also explain the
 * effect — with {@code maxTotalClaimsPerRun} configured to exactly {@link #EXHAUSTED_COUNT}. It
 * then seeds {@link #LEGITIMATE_COUNT} un-reminded, perfectly ordinary candidates with LATER {@code
 * ends_at} (so under the pre-fix statement, the {@code LIMIT} is entirely consumed by the exhausted
 * rows before the {@code ON CONFLICT} guard ever gets a chance to reject them, and the run claims
 * nothing at all).
 */
@TestPropertySource(properties = {
        "booking.closure-reminder.enabled=true",
        "booking.closure-reminder.dry-run=false"
})
@DisplayName("ClosureReminderDedupeStarvationIT — dedupe-exhausted rows must never consume the global cap's LIMIT budget (cycle-2 HIGH fix)")
class ClosureReminderDedupeStarvationIT extends AbstractIntegrationTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2031-06-15T12:00:00Z");
    private static final int MAX_PER_BOOKING = 2;
    private static final int MAX_PER_PROVIDER_PER_DAY = 5; // generous — never binds in this fixture
    private static final int EXHAUSTED_COUNT = 3;
    private static final int LEGITIMATE_COUNT = 5;

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

    /** Seeds a {@code booking_closure_reminders} row already at {@code reminder_count ==
     * maxPerBooking} — permanently un-claimable, regardless of how much time passes, since the
     * {@code ON CONFLICT ... WHERE} guard's {@code reminder_count < maxPerBooking} can never be
     * satisfied again. {@code last_sent_at = NOW} mirrors backend-perf's exact repro fixture. */
    private void markDedupeExhausted(UUID bookingId) {
        jdbcTemplate.update(
                "INSERT INTO booking_closure_reminders (booking_id, reminder_count, last_sent_at) "
                        + "VALUES (?, ?, ?)",
                bookingId, (short) MAX_PER_BOOKING, NOW);
    }

    @Test
    @DisplayName("3 globally-oldest dedupe-exhausted bookings + maxTotalClaimsPerRun=3 must still "
            + "claim the 3 oldest LEGITIMATE candidates — not zero")
    void should_claimLegitimateCandidates_when_globallyOldestRowsAreDedupeExhausted() {
        UUID clientId = fixtures.createUser(
                "closure-starve-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);

        // The EXHAUSTED_COUNT globally-oldest candidates in the window — one master each, so the
        // per-provider cap (5) never binds and cannot explain any observed suppression.
        OffsetDateTime exhaustedBase = NOW.minusDays(13);
        List<UUID> exhaustedIds = new ArrayList<>();
        for (int i = 0; i < EXHAUSTED_COUNT; i++) {
            UUID masterId = fixtures.createIndependentMaster(
                    "closure-starve-exhausted-" + i + "-" + System.nanoTime() + "@beautica.test");
            UUID masterServiceId = fixtures.createIndependentMasterService(masterId);
            UUID bookingId = insertConfirmedBooking(clientId, masterId, masterServiceId, exhaustedBase.plusHours(i));
            markDedupeExhausted(bookingId);
            exhaustedIds.add(bookingId);
        }

        // The LEGITIMATE_COUNT never-reminded candidates — LATER ends_at than every exhausted row
        // (still comfortably inside the [now-14d, now-2h] window), one master each.
        OffsetDateTime legitimateBase = NOW.minusDays(5);
        List<UUID> legitimateIdsOrderedByEndsAt = new ArrayList<>();
        for (int i = 0; i < LEGITIMATE_COUNT; i++) {
            UUID masterId = fixtures.createIndependentMaster(
                    "closure-starve-legit-" + i + "-" + System.nanoTime() + "@beautica.test");
            UUID masterServiceId = fixtures.createIndependentMasterService(masterId);
            UUID bookingId = insertConfirmedBooking(clientId, masterId, masterServiceId, legitimateBase.plusHours(i));
            legitimateIdsOrderedByEndsAt.add(bookingId);
        }

        assertThat(exhaustedBase.plusHours(EXHAUSTED_COUNT - 1))
                .as("fixture sanity: every exhausted row's endsAt must be strictly older than every "
                        + "legitimate row's endsAt, or this test would not reproduce the 'exhausted "
                        + "rows win the globally-oldest race' scenario")
                .isBefore(legitimateBase);

        ClosureReminderWindow window = new ClosureReminderWindow(
                NOW, NOW.minus(Duration.ofDays(14)), NOW.minus(Duration.ofHours(2)), NOW.minusDays(1),
                MAX_PER_PROVIDER_PER_DAY, MAX_PER_BOOKING, EXHAUSTED_COUNT);

        ClosureReminderRunResult result = claimService.claimAndEnqueue(window);

        assertThat(result.claimedIds())
                .as("the run must claim the LEGITIMATE, never-reminded candidates — not silently "
                        + "zero just because dedupe-exhausted rows occupy the globally-oldest "
                        + "ends_at positions")
                .isNotEmpty()
                .hasSize(EXHAUSTED_COUNT)
                .containsExactlyInAnyOrderElementsOf(legitimateIdsOrderedByEndsAt.subList(0, EXHAUSTED_COUNT))
                .as("none of the permanently dedupe-exhausted bookings may ever be (re-)claimed")
                .doesNotContainAnyElementsOf(exhaustedIds);
        assertThat(result.claimable()).isEqualTo(EXHAUSTED_COUNT);
    }
}
