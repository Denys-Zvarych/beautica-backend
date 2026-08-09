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
 * backend-perf MEDIUM fix (Finding 4) — {@code booking.closure-reminder.max-total-claims-per-run}
 * bounds the TOTAL number of bookings claimed in ONE run, independent of {@code
 * maxPerProviderPerDay} (which only bounds claims PER MASTER). Before this fix, a run's total
 * claim volume scaled with {@code distinct_masters x maxPerProviderPerDay} — unbounded as the
 * platform grows — and every {@code CLOSURE_REMINDER} row landed in the SAME {@code
 * notification_outbox} the drain worker services strictly FIFO ({@code
 * NotificationOutboxRepository#claimPendingBatch}), so a large burst could queue ahead of every
 * time-sensitive notification created afterwards.
 *
 * <p>This test seeds enough distinct masters that {@code distinct_masters x
 * maxPerProviderPerDay} EXCEEDS a deliberately small configured global cap (30 masters x 3 = 90
 * theoretical per-provider-capped candidates, against a configured cap of 50) and asserts, per the
 * backend-qa spec for this finding, that {@code result.claimedIds().size()} is capped at the
 * global figure regardless of provider count. Calls {@link ClosureReminderClaimService} directly
 * (same style as {@code ClosureReminderJobIT}) rather than {@link ClosureReminderJob#run()} so the
 * returned {@link ClosureReminderRunResult} is available to assert on — including {@link
 * ClosureReminderRunResult#cappedByGlobalLimit()}, which exists specifically so the dry-run
 * report's {@code suppressedByDedupe} figure does not mislabel global-cap-truncated rows as
 * dedupe suppression (see that field's javadoc).
 */
@TestPropertySource(properties = {
        "booking.closure-reminder.enabled=true",
        "booking.closure-reminder.dry-run=false"
})
@DisplayName("ClosureReminderGlobalCapIT — the global per-run cap binds below the per-provider cap's theoretical maximum (Phase 29.6 backend-perf fix)")
class ClosureReminderGlobalCapIT extends AbstractIntegrationTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2031-06-15T12:00:00Z");
    private static final int MASTER_COUNT = 30;
    private static final int BOOKINGS_PER_MASTER = 5;
    private static final int MAX_PER_PROVIDER_PER_DAY = 3;
    private static final int GLOBAL_CAP = 50;

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

    @Test
    @DisplayName("30 masters x >= 3 eligible bookings each (90 theoretical per-provider-capped "
            + "candidates) yields exactly 50 claims — the configured global cap — regardless of "
            + "the provider count")
    void should_capTotalClaims_when_distinctMastersTimesPerProviderCapExceedsGlobalCap() {
        assertThat((long) MASTER_COUNT * MAX_PER_PROVIDER_PER_DAY)
                .as("fixture must actually exceed the configured global cap, or this test would "
                        + "pass vacuously (the per-provider cap alone would already be under 50)")
                .isGreaterThan(GLOBAL_CAP);

        UUID clientId = fixtures.createUser(
                "closure-globalcap-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        OffsetDateTime base = NOW.minusHours(20);

        for (int m = 0; m < MASTER_COUNT; m++) {
            UUID masterId = fixtures.createIndependentMaster(
                    "closure-globalcap-master-" + m + "-" + System.nanoTime() + "@beautica.test");
            UUID masterServiceId = fixtures.createIndependentMasterService(masterId);
            // 5 non-overlapping 1h bookings per master, 2h apart, comfortably inside the
            // [now-14d, now-2h] window.
            for (int i = 0; i < BOOKINGS_PER_MASTER; i++) {
                insertConfirmedBooking(clientId, masterId, masterServiceId, base.plusHours(2L * i));
            }
        }

        ClosureReminderWindow window = new ClosureReminderWindow(
                NOW, NOW.minus(Duration.ofDays(14)), NOW.minus(Duration.ofHours(2)), NOW.minusDays(1),
                MAX_PER_PROVIDER_PER_DAY, 2, GLOBAL_CAP);

        ClosureReminderRunResult result = claimService.claimAndEnqueue(window);

        assertThat(result.claimedIds())
                .as("claimedIds must be capped at the configured global figure (50), even though "
                        + "the per-provider cap alone would allow up to 30 x 3 = 90")
                .hasSize(GLOBAL_CAP);
        assertThat(result.claimable()).isEqualTo(GLOBAL_CAP);

        // Correctness of the reported stats, not just the claim itself (see the class javadoc):
        // candidates=150 (30 x 5), cappedByProviderLimit=60 (30 masters x 2 excess over the cap
        // of 3), leaving 90 eligible post-provider-cap; the global LIMIT of 50 then truncates 40
        // of those BEFORE the ON CONFLICT dedupe guard ever runs, so cappedByGlobalLimit must
        // carry that 40 — NOT suppressedByDedupe, since nothing here was ever reminded before.
        assertThat(result.candidateCount()).isEqualTo((long) MASTER_COUNT * BOOKINGS_PER_MASTER);
        assertThat(result.cappedByProviderLimit()).isEqualTo((long) MASTER_COUNT * (BOOKINGS_PER_MASTER - MAX_PER_PROVIDER_PER_DAY));
        assertThat(result.cappedByGlobalLimit())
                .as("the 40 rows the global cap truncated must be attributed to the global cap, "
                        + "not mislabelled as dedupe suppression")
                .isEqualTo(90 - GLOBAL_CAP);
        assertThat(result.suppressedByDedupe())
                .as("nothing in this fresh fixture was ever reminded before, so dedupe suppresses "
                        + "nothing once the global-cap rows are correctly excluded")
                .isZero();
    }
}
