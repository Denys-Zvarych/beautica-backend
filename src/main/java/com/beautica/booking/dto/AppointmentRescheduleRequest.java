package com.beautica.booking.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;

/**
 * Request to move a whole {@code CONFIRMED} multi-service visit to a new time — the visit-level
 * analogue of {@link RescheduleBookingRequest}. {@code newStartsAt} is the new start of the FIRST
 * chained service; every remaining service re-lays-out sequentially, back-to-back, preserving
 * order and each item's frozen duration/buffer/price snapshot.
 *
 * <p>The {@code @Future} guard rejects past instants at the controller boundary; the stricter
 * lead-time floor (&ge;15 min ahead) and the &le;180-day cap are enforced in
 * {@code AppointmentTransitionService.rescheduleAppointment} via the shared
 * {@code BookingStartsAtValidator} path — identical bounds to the single-booking reschedule flow.
 */
public record AppointmentRescheduleRequest(
        @NotNull @Future
        OffsetDateTime newStartsAt
) {
}
