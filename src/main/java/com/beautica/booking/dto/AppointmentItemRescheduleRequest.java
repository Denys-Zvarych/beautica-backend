package com.beautica.booking.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;

/**
 * Request to move ONE service line of a multi-service visit to a new time — the per-item
 * counterpart of {@link AppointmentRescheduleRequest} (phase 30.1/30.4).
 *
 * <p><b>Opposite semantics from {@link AppointmentRescheduleRequest}, which is why this is a
 * separate type rather than a reuse</b> (phase 30.1 D11): this request moves ONLY the ONE service
 * line named by the URL's {@code bookingId} — every sibling item keeps its window byte-for-byte
 * unchanged (LOCKED per-item semantics L1/L2). {@link AppointmentRescheduleRequest#newStartsAt}
 * instead re-lays-out EVERY remaining chained service sequentially, back-to-back, from a new first
 * start. Reusing the whole-visit type here would ship a contract whose own documentation
 * contradicts the endpoint it annotates.
 *
 * <p>Field-for-field identical to {@link AppointmentRescheduleRequest} and
 * {@link RescheduleBookingRequest} otherwise: the {@code @Future} guard rejects past instants at
 * the controller boundary; the stricter lead-time floor (&ge;15 min ahead) and the &le;180-day cap
 * are enforced in {@code AppointmentTransitionService.rescheduleAppointmentItem} via the shared
 * {@code BookingStartsAtValidator} path — identical bounds to every other reschedule route.
 */
public record AppointmentItemRescheduleRequest(
        @Schema(description = "The new start of THIS service line only. Siblings keep their "
                + "windows; the visit's items may end up non-contiguous (gaps are legal, overlaps "
                + "are not — phase 30.1 L3).")
        @NotNull @Future
        OffsetDateTime newStartsAt
) {
}
