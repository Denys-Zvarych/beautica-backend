package com.beautica.booking.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;

/**
 * Request to move a {@code CONFIRMED} booking to a new time.
 *
 * <p>The {@code @Future} guard rejects past instants at the controller boundary;
 * the stricter lead-time floor (≥15 min ahead) and the ≤180-day cap are enforced
 * in {@code BookingService.rescheduleBooking} via the shared {@code validateStartsAt}
 * path so the reschedule and create flows apply identical bounds.
 */
public record RescheduleBookingRequest(
        @NotNull @Future
        OffsetDateTime newStartsAt
) {
}
