package com.beautica.notification.sms;

import java.time.Clock;
import java.time.Duration;

/**
 * Phase 441 audit-fix — aggregate signal for SMSClub {@code RECIPIENT_INVALID} rejections.
 *
 * <p>A single rejected recipient number is user input and logs at WARN only. That keeps a regression in our own number
 * formatting, or an abuse flood of well-formed but rejected numbers, out of Sentry entirely — so this counts them in
 * a fixed window: the rejection that brings the window's count to {@code threshold} reports {@code true} (the caller
 * then logs ONE {@code ExternalCallFailure} ERROR), every later one in the same window reports {@code false}, and the
 * count restarts once the window has elapsed. The window opens at the first rejection after the previous one ended.
 *
 * <p>In memory, per instance, lost on restart — deliberately cheap, like {@link StagingSmsDailyCap}. One instance is
 * shared by the booking and the OTP {@link SmsClubService} ({@code SmsConfig}), so the count is a single total.
 * Thread-safe: every state change happens under this instance's monitor.
 */
public class RecipientInvalidSpikeAlarm {

    private final Clock clock;
    private final int threshold;
    private final Duration window;
    private final long windowMillis;
    private long windowStart;
    private int count;

    public RecipientInvalidSpikeAlarm(Clock clock, Integer threshold, Duration window) {
        if (threshold == null || threshold <= 0) {
            throw new IllegalStateException("app.sms.smsclub.recipient-invalid-spike-threshold must be positive");
        }
        if (window == null || window.isNegative() || window.isZero()) {
            throw new IllegalStateException("app.sms.smsclub.recipient-invalid-spike-window must be positive");
        }
        this.clock = clock;
        this.threshold = threshold;
        this.window = window;
        this.windowMillis = window.toMillis();
    }

    /** Counts one rejection; {@code true} exactly once per window, when the count reaches the threshold. */
    public synchronized boolean recordAndCheck() {
        long now = clock.millis();
        if (count == 0 || now - windowStart >= windowMillis) {
            windowStart = now;
            count = 0;
        }
        count++;
        return count == threshold;
    }

    public int threshold() {
        return threshold;
    }

    public Duration window() {
        return window;
    }
}
