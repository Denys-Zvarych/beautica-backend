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
 */
public record GuestBookingResponse(
        UUID bookingId,
        OffsetDateTime startsAt,
        String masterName,
        String serviceName,
        int durationMinutes,
        String cancelUrl
) {
}
