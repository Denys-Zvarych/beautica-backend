package com.beautica.notification.channel;

/**
 * Phase 408 D1 — the guest messages that may go to Telegram instead of SMS. Anything not listed here (OTP,
 * guest / walk-in confirmation with its cancel link, the guest's own cancellation, the reschedule notice) never
 * reaches {@link GuestMessageRouter} and stays SMS-only.
 */
public enum GuestMessageKind {

    GUEST_REMINDER_24H("guest_reminder_24h"),
    GUEST_REMINDER_2H("guest_reminder_2h"),
    GUEST_PROVIDER_CANCELLATION("guest_provider_cancellation"),
    GUEST_REVIEW_INVITE("guest_review_invite");

    private final String token;

    GuestMessageKind(String token) {
        this.token = token;
    }

    /** Stable metric / log token — renaming one is an alerting change. */
    public String token() {
        return token;
    }
}
