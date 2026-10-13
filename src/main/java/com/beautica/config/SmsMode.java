package com.beautica.config;

/**
 * How outbound SMS is delivered (Phase 368). Bound from {@code beautica.sms.mode}.
 * Orthogonal to {@code app.booking.sms.enabled}, which stays the booking-SMS money gate.
 */
public enum SmsMode {
    /** The paid Turbosms provider (default). */
    TURBOSMS,
    /** Mail each SMS to a Mailpit inbox; nothing reaches the provider. Staging only. */
    CAPTURE
}
