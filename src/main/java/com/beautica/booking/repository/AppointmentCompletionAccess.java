package com.beautica.booking.repository;

import java.util.UUID;

/**
 * Batched, multi-appointment sibling of {@link BookingCompletionAccess} (perf MEDIUM, phase 337
 * cycle-2 audit) — one row per {@code Booking} item, carrying its OWN {@code appointmentId} so
 * {@code AuthorizationService.enforceCanManageAppointments} can regroup a single {@code IN (...)}
 * result set back into per-appointment authority checks without ever mixing one appointment's
 * items with a sibling's.
 *
 * <p>Deliberately a distinct record rather than widening {@link BookingCompletionAccess} with a
 * nullable {@code appointmentId}: the single-appointment projection's callers
 * ({@code AuthorizationService#enforceCanManageAppointment}, {@code #canRescheduleAppointment})
 * never need it, and giving them a field they must ignore invites a future caller to read it
 * as "authoritative" from a row that was never grouped.
 *
 * @param appointmentId the appointment (visit) this row's booking belongs to — the regroup key
 * @param masterUserId  the user id of the booking's master (used for the INDEPENDENT_MASTER branch)
 * @param salonId       the id of the master's salon, or {@code null} for an independent master
 */
public record AppointmentCompletionAccess(
        UUID appointmentId,
        UUID masterUserId,
        UUID salonId
) {}
