package com.beautica.booking.repository;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Narrow projection backing the salon-deletion booking cascade (Phase 269/293):
 * {@code BookingService#declineFutureConfirmedBookingsForSalonClosure}. Carries exactly the
 * columns needed to (a) route the eventual decline — standalone
 * ({@code BookingService#declineBookingForBatch}) vs. appointment-child
 * ({@code AppointmentTransitionService#declineAppointmentItems}), grouped by
 * {@link #visitKey()} — and (b) pick the deterministic representative booking of each visit for
 * the single {@code SALON_CLOSED} outbox entry (D12: lowest {@code startsAt}, tied on
 * {@code bookingId}) — without hydrating a full {@code Booking}/{@code User} entity graph for
 * what can be a salon-wide scan. Mirrors {@code OverrideConflictCandidate}'s shape and purpose.
 *
 * <p><b>Widened, scope-agnostic use (Phase 298 D3).</b> The exact same projection also backs the
 * master-removal booking cascade ({@code BookingService
 * #declineFutureConfirmedBookingsForMasterRemoval}, {@code BookingRepository
 * #findConfirmedFutureByMasterId}) — every field here is already scope-agnostic (a booking id, its
 * optional appointment id, its master id, its start instant), so only the scanning query's {@code
 * WHERE} clause differs between the salon-scoped and master-scoped callers, never this record.
 * The name is narrower than its use as a result; renaming it to something scope-neutral (e.g.
 * {@code FutureBookingCandidate}) is a reasonable follow-up, deliberately not done here — it would
 * touch every Phase 293 call site for cosmetic reasons only.
 *
 * @param bookingId     the booking's id
 * @param appointmentId non-null iff this is one leg of a multi-service visit
 * @param masterId      the booking's master — used to scope the after-commit cache eviction to
 *                      exactly the masters actually touched by this cascade
 * @param startsAt      absolute start instant — the D3 boundary is applied by the query itself
 *                      ({@code startsAt > now}), so every row returned is already future
 */
public record SalonClosureBookingCandidate(
        UUID bookingId,
        UUID appointmentId,
        UUID masterId,
        OffsetDateTime startsAt
) {

    /**
     * The key every candidate of the SAME visit shares: {@code coalesce(appointmentId, bookingId)}
     * — the exact grouping the locked one-notice-per-visit rule
     * ({@code phase-260-22.13-one-sms-per-visit-and-visit-counted-budget.md}) requires. A
     * standalone booking (no appointment) is a visit of one, keyed to its own id.
     */
    public UUID visitKey() {
        return appointmentId != null ? appointmentId : bookingId;
    }
}
