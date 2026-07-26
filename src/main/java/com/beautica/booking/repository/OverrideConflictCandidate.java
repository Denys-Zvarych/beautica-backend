package com.beautica.booking.repository;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Narrow projection backing the "schedule override over existing bookings" feature (2026-07-26
 * design): exactly the columns {@code ScheduleOverrideConflictService} needs to (a) render an
 * {@code OverrideConflictResponse} row and (b) route the eventual cancellation — standalone decline
 * vs. {@code AppointmentTransitionService#declineAppointmentItem} — without hydrating a full
 * {@code Booking}/{@code User} entity graph for what can be a wide date-range scan.
 *
 * @param bookingId        the booking's id
 * @param appointmentId    non-null iff this is one leg of a multi-service visit
 * @param startsAt         absolute start instant
 * @param endsAt           absolute end instant (exclusive)
 * @param clientFirstName  the registered client's first name, or {@code null} for a guest booking
 * @param clientLastName   the registered client's last name, or {@code null} for a guest booking
 * @param guestName        the guest's first name (LINK bookings only), or {@code null}
 * @param guestSurname     the guest's surname (LINK bookings only), or {@code null}
 * @param serviceName      the booked service's display name
 */
public record OverrideConflictCandidate(
        UUID bookingId,
        UUID appointmentId,
        OffsetDateTime startsAt,
        OffsetDateTime endsAt,
        String clientFirstName,
        String clientLastName,
        String guestName,
        String guestSurname,
        String serviceName
) {

    /**
     * Registered-client name when present (a {@code client_id IS NOT NULL} row — V89's
     * {@code chk_bookings_guest_fields} guarantees {@code clientFirstName}/{@code clientLastName}
     * are non-null in that case), else the guest's name for a {@code LINK} booking.
     */
    public String clientDisplayName() {
        if (clientFirstName != null || clientLastName != null) {
            return joinNonBlank(clientFirstName, clientLastName);
        }
        return joinNonBlank(guestName, guestSurname);
    }

    private static String joinNonBlank(String first, String second) {
        StringBuilder result = new StringBuilder();
        if (first != null && !first.isBlank()) {
            result.append(first);
        }
        if (second != null && !second.isBlank()) {
            if (!result.isEmpty()) {
                result.append(' ');
            }
            result.append(second);
        }
        return result.toString();
    }
}
