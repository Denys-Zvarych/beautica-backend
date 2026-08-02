package com.beautica.booking.closure;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.BookingTestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 29.7 — QA gate closure. {@code ClosureReminderJobTest} proves {@link
 * ClosureReminderProperties#getLookbackMax()} clamps a configured ceiling above {@link
 * ClosureReminderProperties#LOOKBACK_MAX_HARD_CLAMP} (30 days) purely in memory, and that {@link
 * ClosureReminderJob#resolveWindow()} reads the clamped getter rather than the raw field. Neither
 * proves the clamp actually reaches the database: {@link ClosureReminderRepository#claimDueReminders}
 * takes whatever {@code windowStart} it is handed and has no independent knowledge of "raw" vs
 * "clamped" — a regression that swapped {@code getLookbackMax()} for {@code getLookbackMaxRaw()}
 * anywhere upstream would sail past every existing test in this package.
 *
 * <p>This test closes that gap end-to-end: a real Spring context with {@code
 * booking.closure-reminder.lookback-max=90d} (a deliberately misconfigured value past the 30-day
 * ceiling), the REAL {@link ClosureReminderJob#run()} entry point (property binding -&gt;
 * resolveWindow -&gt; claim -&gt; Postgres), and two bookings: one 40 days elapsed (inside the
 * configured 90d, outside the 30-day hard clamp) and one 10 days elapsed (inside both). Only the
 * 10-day booking may end up in {@code booking_closure_reminders}.
 */
@TestPropertySource(properties = {
        "booking.closure-reminder.enabled=true",
        "booking.closure-reminder.dry-run=false",
        "booking.closure-reminder.lookback-max=90d"
})
@DisplayName("ClosureReminderLookbackClampIT — the 30-day hard clamp survives a misconfigured lookback-max, end-to-end (Phase 29.7)")
class ClosureReminderLookbackClampIT extends AbstractIntegrationTest {

    @Autowired
    private ClosureReminderJob job;

    @Autowired
    private ClosureReminderRepository closureRepository;

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

    @Test
    @DisplayName("job.run() with lookback-max=90d configured still excludes a 40-day-elapsed booking "
            + "(hard-clamped to 30d) while claiming a 10-day-elapsed booking from a different master")
    void should_excludeBookingBeyondHardClamp_when_configuredLookbackMaxExceeds30Days() {
        UUID clientId = fixtures.createUser(
                "closure-clamp-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        UUID tooOldMasterId = fixtures.createIndependentMaster(
                "closure-clamp-toooold-" + System.nanoTime() + "@beautica.test");
        UUID tooOldServiceId = fixtures.createIndependentMasterService(tooOldMasterId);
        UUID tooOldBookingId = insertConfirmedBooking(clientId, tooOldMasterId, tooOldServiceId, now.minusDays(40));

        UUID eligibleMasterId = fixtures.createIndependentMaster(
                "closure-clamp-eligible-" + System.nanoTime() + "@beautica.test");
        UUID eligibleServiceId = fixtures.createIndependentMasterService(eligibleMasterId);
        UUID eligibleBookingId = insertConfirmedBooking(clientId, eligibleMasterId, eligibleServiceId, now.minusDays(10));

        job.run();

        assertThat(closureRepository.findById(tooOldBookingId))
                .as("40-day-elapsed booking is inside the configured 90d lookback-max but OUTSIDE "
                        + "the 30-day hard clamp — must never be claimed")
                .isEmpty();
        assertThat(closureRepository.findById(eligibleBookingId))
                .as("10-day-elapsed booking is inside both the configured 90d value and the "
                        + "30-day hard clamp — must be claimed")
                .isPresent();
    }
}
