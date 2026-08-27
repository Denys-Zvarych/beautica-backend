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
 *
 * @param allowClientOverlap explicit, client-supplied opt-in (product decision 2026-08-22, widened
 *   2026-08-26 to every booking write path) to allow the new window to overlap the client's OWN
 *   other {@code CONFIRMED} booking(s). Same contract as
 *   {@link CreateBookingRequest#allowClientOverlap()} / {@link RescheduleBookingRequest#allowClientOverlap()}:
 *   defaults to {@code false} (a primitive {@code boolean}, so an absent field on the wire
 *   deserializes to {@code false} and every existing caller is byte-for-byte unaffected). When
 *   {@code true}, ONLY {@code AppointmentTransitionService#assertNoClientConflictExcludingBooking}
 *   is skipped for this item — the in-visit {@code assertNoSiblingOverlap} check, the
 *   master-scoped {@code existsOverlapExcluding} check and the DB-level
 *   {@code no_overlapping_bookings} EXCLUDE constraint all still run unconditionally regardless of
 *   this flag.
 */
public record AppointmentItemRescheduleRequest(
        @Schema(description = "The new start of THIS service line only. Siblings keep their "
                + "windows; the visit's items may end up non-contiguous (gaps are legal, overlaps "
                + "are not — phase 30.1 L3).")
        @NotNull @Future
        OffsetDateTime newStartsAt,

        boolean allowClientOverlap
) {
}
