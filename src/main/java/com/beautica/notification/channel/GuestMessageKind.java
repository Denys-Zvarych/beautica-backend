package com.beautica.notification.channel;

/**
 * Phase 408 D1 — the guest messages that may go to Telegram instead of SMS. User decision 2026-11-04: EVERY guest
 * notification is listed here (Telegram when the phone is linked, SMS otherwise and on any Telegram failure). The one
 * exception is the guest phone-OTP, which is an auth credential sent through {@code OtpSmsSender}, never reaches
 * {@link GuestMessageRouter} and always stays SMS. (The guest's own cancellation sends no guest message since phase 435.)
 */
public enum GuestMessageKind {

    /** Public guest booking confirmation + cancel link (2026-11-04: routed, was SMS-only). */
    GUEST_CONFIRMATION("guest_confirmation"),
    /** Walk-in confirmation + cancel link (2026-11-04: routed, was SMS-only). */
    WALK_IN_CONFIRMATION("walk_in_confirmation"),

    GUEST_REMINDER_24H("guest_reminder_24h"),
    GUEST_REMINDER_2H("guest_reminder_2h"),
    GUEST_PROVIDER_CANCELLATION("guest_provider_cancellation"),
    /**
     * Phase 435 follow-up (user decision 2026-11-04, "same sms should be in telegram") — the provider moved the guest's
     * booking (phase 431 notice). Gates and the per-phone budget stay in {@code NotificationService}, before routing.
     */
    GUEST_PROVIDER_RESCHEDULE("guest_provider_reschedule"),
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
