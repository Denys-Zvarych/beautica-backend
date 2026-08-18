package com.beautica.booking.dto;

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
 */
public record GuestBookingResponse(
        UUID bookingId,
        UUID appointmentId,
        OffsetDateTime startsAt,
        String masterName,
        String serviceName,
        int durationMinutes,
        String cancelUrl
) {

    /**
     * Legacy 6-arg factory for a single-service guest booking (no appointment) — preserves the
     * pre-BE-7 call-site and JSON shape ({@code appointmentId} defaults to {@code null}).
     */
    public GuestBookingResponse(
            UUID bookingId, OffsetDateTime startsAt, String masterName,
            String serviceName, int durationMinutes, String cancelUrl) {
        this(bookingId, null, startsAt, masterName, serviceName, durationMinutes, cancelUrl);
    }
}
