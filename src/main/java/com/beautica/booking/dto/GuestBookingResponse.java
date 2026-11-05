package com.beautica.booking.dto;

import com.beautica.notification.telegram.link.dto.TelegramAvailability;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Response for a successful guest booking ({@code 201 Created}, Phase 13.3).
 *
 * <p>Anti-Bug §I-2: this is returned on a {@code permitAll} endpoint, so it carries
 * NO master/salon/owner UUIDs — only the booking id (the caller needs it), the
 * confirmed slot, the human-readable master + service names, the duration, and the
 * cancel URL. {@code guestPhone} is never echoed back.
 *
 * <p><b>BE-7 multi-service visit.</b> {@code appointmentId} is {@code null} for a legacy
 * single-service booking and set for a multi-service visit; for a visit {@code bookingId} is the
 * first chained item, {@code serviceName} the first service, and {@code durationMinutes} the summed
 * service duration. The visit-level cancel token (which cancels ALL items) is embedded in
 * {@code cancelUrl} — the token itself is never returned as a bare field.
 *
 * <p><b>Phase 407 D6 (additive).</b> {@code telegram} = {@code {available, linked}} for the booker's OTP-verified
 * phone, once per response (a salon multi-master checkout is one call per master). An older site ignores it.
 */
public record GuestBookingResponse(
        UUID bookingId,
        UUID appointmentId,
        OffsetDateTime startsAt,
        String masterName,
        String serviceName,
        int durationMinutes,
        String cancelUrl,
        TelegramAvailability telegram
) {

    /** Pre-407 7-arg shape ({@code telegram} {@code null}) — kept for existing call-sites; see {@link #withTelegram}. */
    public GuestBookingResponse(
            UUID bookingId, UUID appointmentId, OffsetDateTime startsAt, String masterName,
            String serviceName, int durationMinutes, String cancelUrl) {
        this(bookingId, appointmentId, startsAt, masterName, serviceName, durationMinutes, cancelUrl, null);
    }

    /**
     * Legacy 6-arg factory for a single-service guest booking (no appointment) — preserves the
     * pre-BE-7 call-site and JSON shape ({@code appointmentId} defaults to {@code null}).
     */
    public GuestBookingResponse(
            UUID bookingId, OffsetDateTime startsAt, String masterName,
            String serviceName, int durationMinutes, String cancelUrl) {
        this(bookingId, null, startsAt, masterName, serviceName, durationMinutes, cancelUrl, null);
    }

    /** Phase 407 D6 — the same response with its {@code telegram} block set. */
    public GuestBookingResponse withTelegram(TelegramAvailability availability) {
        return new GuestBookingResponse(bookingId, appointmentId, startsAt, masterName, serviceName, durationMinutes,
                cancelUrl, availability);
    }
}
