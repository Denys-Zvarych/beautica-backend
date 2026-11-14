package com.beautica.notification.service;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * Phase 494 — which reminder a {@code BOOKING_REMINDER} outbox row is (NTF-47), with the sweep window its claim
 * was made over. Written into the payload by name, so the constant names are part of the stored format.
 *
 * <p>The windows are the guest sweeps' (GST-46): 24 h = {@code [now+23h, now+25h]}, 2 h =
 * {@code [now+1h45, now+2h15)}. {@code BookingReminderJob} reads them from here, so the sweep and the drain-time
 * re-check can never disagree.
 */
public enum ReminderKind {

    H24(Duration.ofHours(23), Duration.ofHours(25)),
    H2(Duration.ofMinutes(105), Duration.ofMinutes(135));

    private final Duration windowStart;
    private final Duration windowEnd;

    ReminderKind(Duration windowStart, Duration windowEnd) {
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
    }

    public Duration windowStart() {
        return windowStart;
    }

    public Duration windowEnd() {
        return windowEnd;
    }

    /**
     * Whether a booking starting at {@code startsAt} still sits in the window a sweep at {@code claimedAt} selected
     * it from (both bounds inclusive — the drain check only has to catch a start MOVED away, never a boundary tie).
     */
    public boolean isInWindow(OffsetDateTime startsAt, Instant claimedAt) {
        Instant start = startsAt.toInstant();
        return !start.isBefore(claimedAt.plus(windowStart)) && !start.isAfter(claimedAt.plus(windowEnd));
    }
}
