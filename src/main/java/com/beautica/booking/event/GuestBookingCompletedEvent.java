package com.beautica.booking.event;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Phase 400 D7 — a single guest (LINK) booking with no visit header ({@code bookings.appointment_id IS NULL})
 * reached {@code COMPLETED}: published by {@code BookingService#completeBooking}, inside the completing transaction.
 * Consumed AFTER_COMMIT by {@code ReviewInviteScheduler}, which creates at most one invite per booking.
 *
 * <p>It never double-fires with {@link GuestVisitCompletedEvent}: {@code completeBooking} refuses appointment
 * children ({@code assertNotAppointmentChild}). Carries a {@code bookings.id} — never an {@code appointments.id}.
 *
 * @param bookingId   the completed booking id ({@code bookings.id})
 * @param completedAt server-clock instant of the completion; the invite's {@code send_after} is derived from it
 */
public record GuestBookingCompletedEvent(UUID bookingId, Instant completedAt) {

    public GuestBookingCompletedEvent {
        Objects.requireNonNull(bookingId, "bookingId");
        Objects.requireNonNull(completedAt, "completedAt");
    }
}
