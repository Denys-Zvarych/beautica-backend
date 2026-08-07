package com.beautica.booking.event;

import java.util.UUID;

/**
 * Published when a booking (or a whole multi-service visit) reaches {@code COMPLETED}.
 *
 * <h2>Why this event is new (2026-08 perf audit F3)</h2>
 * Before the BEAUTY PASSPORT cache there was no Spring event for booking completion at all —
 * the two completion paths ({@code BookingService#completeBooking} and
 * {@code AppointmentTransitionService#completeAppointment}) signalled downstream work through
 * the notification outbox and through direct {@code TransactionSynchronizationManager}
 * eviction helpers. Neither seam is usable from another feature package without importing it,
 * so completion is now announced as a domain fact and the {@code client} package subscribes.
 * This is a deliberate addition, not a repurposing of an existing event.
 *
 * <p>COMPLETED is the status the passport aggregates over ({@code bookingsConsidered}, top
 * districts/cities, budget band), so this is the invalidation trigger for
 * {@link com.beautica.client.service.ClientPassportService#CLIENT_PASSPORT_CACHE}.
 *
 * <p><b>{@code clientUserId} is nullable.</b> Guest (LINK) bookings have no account
 * ({@code V89 chk_bookings_guest_fields}) — there is no passport to invalidate, and listeners
 * must null-check rather than assume. Every other consumer of this event must treat a null
 * client the same way the review-prompt enqueue already does: skip.
 *
 * <h2>Why there is no id field (2026-08 security audit LOW)</h2>
 * This record briefly carried a {@code bookingId}. No consumer ever read it, and the two publish
 * sites filled it from <b>two disjoint id spaces</b>: the single-booking path passed a
 * {@code bookings.id} while the multi-service visit path passed an {@code appointments.id}. A
 * future listener writing the obvious {@code bookingRepository.findById(event.bookingId())}
 * would therefore get an empty {@code Optional} for every multi-service visit — the
 * failed-lookup-treated-as-success class of bug, silent by construction. The field is dropped
 * rather than renamed because nothing needs it: the only invalidation key is the client.
 *
 * <p><b>If an id is ever added back it MUST carry a discriminator</b> (e.g. a sealed
 * {@code CompletedSubject} of {@code SingleBooking(UUID)} / {@code Visit(UUID)}), never a bare
 * {@code UUID} whose table depends on which path published it.
 *
 * @param clientUserId {@code users.id} of the client, or {@code null} for a guest booking
 */
public record BookingCompletedEvent(UUID clientUserId) {}
