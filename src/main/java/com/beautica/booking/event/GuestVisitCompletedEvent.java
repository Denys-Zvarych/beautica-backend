package com.beautica.booking.event;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Phase 400 D1 — a booking of a guest (LINK) multi-service visit reached {@code COMPLETED}: published by
 * {@code AppointmentTransitionService#completeAppointment} (whole visit) and {@code #completeAppointmentItem} (one
 * service line), once per completion call, inside the completing transaction. Consumed AFTER_COMMIT by the review
 * package ({@code ReviewInviteScheduler}), which creates at most one invite per visit — so an item-level completion
 * followed by the header completion is harmless: the first one wins.
 *
 * <p>Only LINK visits publish it; the consumer still re-checks the source on fresh data. Carries an
 * {@code appointments.id} — never a {@code bookings.id} (see {@link BookingCompletedEvent}'s note on mixed id spaces).
 *
 * @param appointmentId the visit header id ({@code appointments.id})
 * @param completedAt   server-clock instant of this completion; the invite's {@code send_after} is derived from the
 *                      first one
 */
public record GuestVisitCompletedEvent(UUID appointmentId, Instant completedAt) {

    public GuestVisitCompletedEvent {
        Objects.requireNonNull(appointmentId, "appointmentId");
        Objects.requireNonNull(completedAt, "completedAt");
    }
}
