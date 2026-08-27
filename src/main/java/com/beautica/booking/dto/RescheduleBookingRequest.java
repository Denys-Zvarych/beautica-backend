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
 *
 * @param allowClientOverlap explicit, client-supplied opt-in (product decision 2026-08-22,
 *   widened 2026-08-26 to every booking write path) to allow the new window to overlap the
 *   client's OWN other {@code CONFIRMED} booking(s). Same contract as
 *   {@link CreateBookingRequest#allowClientOverlap()}: defaults to {@code false} (a primitive
 *   {@code boolean}, so an absent field on the wire deserializes to {@code false} and every
 *   existing caller is byte-for-byte unaffected). When {@code true}, ONLY
 *   {@code BookingService#rescheduleBooking}'s {@code assertNoClientConflictExcluding} self-conflict
 *   check is skipped — the master-scoped {@code existsOverlapExcluding} check and the DB-level
 *   {@code no_overlapping_bookings} EXCLUDE constraint (which protect a DIFFERENT client's booking
 *   on the same master slot) still run exactly as before and can never be bypassed by this flag.
 */
public record RescheduleBookingRequest(
        @NotNull @Future
        OffsetDateTime newStartsAt,

        boolean allowClientOverlap
) {
}
