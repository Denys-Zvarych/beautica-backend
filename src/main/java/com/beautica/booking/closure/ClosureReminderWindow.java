package com.beautica.booking.closure;

import java.time.OffsetDateTime;

/**
 * The resolved, absolute-instant bounds one {@link ClosureReminderJob} run operates against —
 * computed ONCE, from the injected {@link java.time.Clock} (Anti-Bug §G, never {@code
 * Instant.now()}), and threaded through {@link ClosureReminderClaimService} unchanged. Nothing in
 * {@link com.beautica.common.TimeZones#KYIV} may appear here — see {@code
 * com.beautica.booking.domain.BookingClosureRule}'s javadoc for why the candidate predicate stays
 * an absolute-instant comparison; the job's cron trigger is the ONE legitimate Kyiv reference in
 * this whole track.
 */
public record ClosureReminderWindow(
        OffsetDateTime now,
        OffsetDateTime windowStart,
        OffsetDateTime windowEnd,
        OffsetDateTime dedupeCooldownBefore,
        int maxPerProviderPerDay,
        int maxPerBooking,
        int maxTotalClaimsPerRun) {
}
