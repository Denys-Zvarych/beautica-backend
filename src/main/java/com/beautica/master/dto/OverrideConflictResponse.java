package com.beautica.master.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One {@code CONFIRMED} booking that would be orphaned by an intended schedule override
 * (2026-07-26 design — "schedule override over existing bookings"). Returned by the read-only
 * {@code POST /api/v1/masters/{masterId}/overrides/conflicts} preview, and used (as the count +
 * per-booking list) to drive the mobile confirmation dialog before the master commits to
 * {@code cancelOverlapping = true} on the write.
 *
 * @param bookingId         the booking's id — the target of the eventual decline, whichever path it
 *                          takes
 * @param appointmentId     non-null iff this booking is one leg of a multi-service visit — the
 *                          write path then declines it via
 *                          {@code AppointmentTransitionService#declineAppointmentItem} rather than
 *                          the standalone decline path
 * @param date              the booking's Kyiv-local calendar date (which date in the requested
 *                          range this conflict belongs to)
 * @param startsAt          the booking's absolute start instant
 * @param endsAt            the booking's absolute end instant
 * @param clientDisplayName the registered client's display name, or the guest's name for a
 *                          {@code LINK}/guest booking — never the client's internal user id or
 *                          contact details
 * @param serviceName       the booked service's display name
 */
public record OverrideConflictResponse(
        UUID bookingId,
        UUID appointmentId,
        LocalDate date,
        OffsetDateTime startsAt,
        OffsetDateTime endsAt,
        String clientDisplayName,
        String serviceName
) {
}
