package com.beautica.booking.domain;

import com.beautica.master.entity.Master;

/**
 * Phase 32 (2026-08 security re-audit HIGH) — the SINGLE canonical Java definition of
 * "this master may be booked / may expose bookable slots".
 *
 * <p><b>The rule (source of truth):</b>
 *
 * <pre>
 * BOOKABLE iff masters.is_active = true
 *          AND (master.salon IS NULL OR salon.is_active = true)
 * </pre>
 *
 * <p><b>Why the salon term exists.</b> {@code SalonService.deactivateSalon} flips
 * {@code salons.is_active = false} but does <b>not</b> cascade to {@code masters.is_active}, so
 * every master of a closed salon still passes {@code Master::isActive}. Before this rule the
 * three booking-create paths each re-implemented only the {@code isActive} half, and a caller
 * holding a {@code masterServiceId} (stale wish-list card, bookmarked deep link, cached
 * catalogue page) could still create a {@code CONFIRMED} booking against a salon the owner had
 * closed.
 *
 * <p><b>{@code getSalon() == null} MUST pass.</b> An {@code INDEPENDENT_MASTER} has no salon and
 * stays bookable. Writing this as a plain {@code salon.isActive()} deref — or, in JPQL, as the
 * implicit path {@code m.salon.isActive} — silently drops every independent master, because
 * Hibernate 6 compiles an implicit to-one path predicate to an INNER join. That is why the JPQL
 * mirror below uses an explicit {@code LEFT JOIN FETCH} + aliased {@code (s IS NULL OR ...)}.
 *
 * <h2>Where this rule is enforced — the direct call sites</h2>
 *
 * <p>This class is the predicate for every path that holds a {@link Master} <em>entity</em>:
 *
 * <ol>
 *   <li>{@code BookingService#doCreateBooking} — {@code POST /bookings} (single service)</li>
 *   <li>{@code AppointmentService#doCreateAppointment} — {@code POST /appointments} (visit)</li>
 *   <li>{@code SlotCalculationService#getAvailableSlots} — {@code GET /masters/{id}/slots}</li>
 *   <li>{@code SlotCalculationService#getBookableWorkingDays} — {@code GET .../working-days}</li>
 *   <li>{@code SlotCalculationService#hasBookableFutureSlot} — catalogue/master-list gate</li>
 *   <li>{@code FavoriteService#validateServiceTarget} — {@code POST /favorites} (wish-list add).
 *       The one write path that deliberately admits {@code SALON_MASTER} services, so it is the
 *       one that can attach a closed salon's service to a client's wish list.</li>
 * </ol>
 *
 * <h2>Derivative enforcers — the three reschedule routes</h2>
 *
 * <p>These do <b>not</b> call this class. They enforce the rule <em>transitively</em>, by asking
 * {@code SlotCalculationService#getAvailableSlots} (call site 3 above) for the target time and
 * rejecting a start that matches no returned slot. Because site 3 returns an EMPTY list for a
 * master this rule fails, an unbookable master's reschedule cannot succeed:
 *
 * <ol>
 *   <li>{@code BookingService#rescheduleBooking} — via {@code assertStartsOnAvailableSlot}</li>
 *   <li>{@code AppointmentTransitionService#rescheduleAppointment} — via
 *       {@code assertVisitStartsOnAvailableSlot} (multi-service overload)</li>
 *   <li>{@code AppointmentTransitionService#rescheduleAppointmentItem} — via
 *       {@code assertItemStartsOnAvailableSlot}</li>
 * </ol>
 *
 * <p><b>For these three routes site 3 IS the security boundary, not a UX nicety.</b> There is no
 * second, independent check downstream: deleting or weakening the guard in
 * {@code getAvailableSlots} would let a client move a live booking onto a master whose salon has
 * since closed. If that guard is ever moved, the three routes above need their own call to this
 * class in the same change.
 *
 * <p><b>The sixth site is a JPQL mirror, not a caller.</b> The public slug paths
 * ({@code GET /book/{slug}/info} via {@code BookingSlugService#findBySlug}, and
 * {@code POST /book/{slug}/booking} via {@code GuestBookingService#createGuestBooking}) resolve
 * the master by slug, so the rule is pushed into the finder itself —
 * {@code MasterRepository#findByBookingSlugWithUser} carries
 * {@code AND m.isActive = true AND (s IS NULL OR s.isActive = true)}. That JPQL predicate and
 * {@link #isBookable(Master)} MUST stay semantically identical: changing one without the other
 * re-opens exactly the gap this class was created to close.
 *
 * <p><b>Callers must ensure {@code master.salon} is initialised</b> before calling — this rule
 * dereferences the association and will otherwise trigger a lazy load (Anti-Bug §E). The
 * finders feeding the call sites above all fetch the salon:
 *
 * <ul>
 *   <li>{@code MasterRepository#findByIdWithUserAndSalon} — {@code LEFT JOIN FETCH}</li>
 *   <li>{@code MasterServiceRepository#findByMasterIdAndIdWithGraph} — {@code LEFT JOIN FETCH}</li>
 *   <li>{@code MasterServiceRepository#findByIdWithServiceDefinitionAndMaster} —
 *       {@code LEFT JOIN FETCH}</li>
 *   <li>{@code MasterServiceRepository#findBookableAssignmentsBySalonAndServiceDef} —
 *       <b>{@code JOIN FETCH} (INNER), deliberately.</b> That query is salon-scoped
 *       ({@code WHERE s.id = :salonId}), so a non-null salon was already required and the inner
 *       join drops no row an outer join would have kept; independent masters were never in its
 *       result set. The {@code LEFT} form is mandatory only on the master-scoped finders, which
 *       must keep {@code salon IS NULL} rows.</li>
 * </ul>
 */
public final class MasterBookability {

    private MasterBookability() {
    }

    /**
     * {@code true} when the master is active AND is either independent (no salon) or employed by
     * a salon that is still open. See the class javadoc for the full rationale.
     *
     * @param master the master to test; {@code null} is never bookable
     */
    public static boolean isBookable(Master master) {
        return master != null
                && master.isActive()
                && (master.getSalon() == null || master.getSalon().isActive());
    }
}
