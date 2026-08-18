package com.beautica.booking.service;

import com.beautica.booking.dto.AvailableSlotResponse;
import com.beautica.common.TimeZones;
import com.beautica.common.exception.BusinessException;
import com.beautica.service.entity.MasterServiceAssignment;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Shared single-service schedule-fit guard (perf audit F3, cross-batch) — the ONE implementation
 * behind BOTH {@code BookingService#assertStartsOnAvailableSlot} and
 * {@code AppointmentTransitionService#assertItemStartsOnAvailableSlot}, which were byte-for-byte
 * duplicates of each other before this extraction (each one's own Javadoc said it "mirrors ...
 * exactly") — zero runtime cost to duplicate, but a real drift risk for two guards that must always
 * agree on what "on schedule" means.
 *
 * <p><b>Why a static utility, not a shared bean.</b> Mirrors {@link BookingTemporalGuard}'s shape
 * exactly: a package-private final class with static methods, in the SAME package as its two
 * callers, so neither needs a new constructor-injected dependency. This matters for the bean graph:
 * {@code BookingService} already depends on {@code AppointmentTransitionService} (for the per-item
 * client-cancel header-lock seam), so a reverse {@code AppointmentTransitionService →
 * BookingService} edge — which a shared instance method living on either class would risk — would
 * cycle. A static method that takes the caller's OWN already-injected {@link SlotCalculationService}
 * as a plain parameter needs no bean of its own, so no such edge is even possible.
 */
final class BookingSlotAvailabilityGuard {

    private BookingSlotAvailabilityGuard() {
    }

    /**
     * Reuses the create-path effective-day / working-hours oracle
     * ({@link SlotCalculationService#getAvailableSlots(UUID, java.time.LocalDate, UUID)}): a start is
     * bookable only if it matches a slot returned by that resolver for the master + service on that
     * date. That resolver applies the effective-day model (weekly templates, per-date overrides,
     * day-offs), master/service liveness, and duration bounds — so a request to a time the master
     * does not work resolves to no matching slot.
     *
     * <p>Compared by {@link OffsetDateTime#isEqual} on the slot start instant (the slot list is
     * generated on {@code SLOT_STEP} boundaries in Kyiv time; {@code isEqual} ignores the
     * offset/zone representation). A non-matching start throws {@code 409 "Slot not available"}.
     *
     * <p><b>Also the CREATE-path schedule-fit gate (2026-08-11).</b> This guard used to have exactly ONE
     * call site — the reschedule path — so {@code POST /bookings}, {@code POST /appointments} and the
     * public {@code POST /book/{slug}/booking} enforced only lead-time/horizon, the client-conflict check
     * and overlap-with-existing, and NEVER schedule fit: a start on a day-off, inside a lunch-break gap,
     * or on an off-grid minute was persisted {@code CONFIRMED} and appeared on the master's calendar. All
     * three create paths now call this, guest/LINK included.
     *
     * <p><b>{@code preloaded} (Perf MEDIUM, 2026-08-11).</b> The assignment the CALLER already resolved
     * with {@code findByMasterIdAndIdWithGraph}, so the availability read does not issue that identical
     * finder a second time in the same persistence context. Both CREATE callers hold one; the RESCHEDULE
     * caller does not and passes {@code null}, which restores the plain reload. It is verified against the
     * request inside {@link SlotCalculationService} and any mismatch falls back to the DB, so it can never
     * change the verdict — see that method's Javadoc.
     *
     * @param slotCalculationService the CALLER's own injected instance — never constructed here
     */
    static void assertStartsOnAvailableSlot(
            SlotCalculationService slotCalculationService, UUID masterId, UUID masterServiceId,
            MasterServiceAssignment preloaded, OffsetDateTime startsAt) {
        assertMatches(
                slotCalculationService.getAvailableSlots(
                        masterId, kyivDate(startsAt), masterServiceId, preloaded),
                startsAt);
    }

    /**
     * Multi-service (BE-2) counterpart of {@link #assertStartsOnAvailableSlot}: the visit's FIRST start
     * must match a slot of the ordered {@code masterServiceIds}' chained block, whose length is the Σ of
     * their effective durations — see
     * {@link SlotCalculationService#getAvailableSlots(UUID, LocalDate, List)}. The chained items are
     * contiguous by construction at create time, so pinning the first start pins the whole block.
     *
     * <p>Deliberately NOT expressed as N single-service calls: a per-item check would accept a first
     * service that fits alone while the CHAIN runs past the master's working window.
     *
     * <p>Shared by {@code AppointmentService#doCreateAppointment}, {@code GuestBookingService}'s LINK
     * visit create and {@code AppointmentTransitionService#rescheduleAppointment} — which previously
     * carried a byte-for-byte duplicate of this body, the exact drift risk this class exists to remove.
     *
     * <p><b>{@code preloaded} (Perf MEDIUM, 2026-08-11).</b> The PARALLEL assignment list the caller's
     * {@code VisitPlanner#planChainedItems} already resolved (same size, same order as
     * {@code masterServiceIds}), so the availability read does not re-run {@code findByMasterIdAndIdWithGraph}
     * once per chained service. The reschedule caller has no such list and passes {@code null}. Verified
     * element-wise inside {@link SlotCalculationService}; any mismatch falls back to the DB load.
     */
    static void assertVisitStartsOnAvailableSlot(
            SlotCalculationService slotCalculationService, UUID masterId, List<UUID> masterServiceIds,
            List<MasterServiceAssignment> preloaded, OffsetDateTime startsAt) {
        assertMatches(
                slotCalculationService.getAvailableSlots(
                        masterId, kyivDate(startsAt), masterServiceIds, preloaded),
                startsAt);
    }

    /**
     * The Kyiv CIVIL date {@code startsAt} falls on — the date key every availability read is scoped by
     * ({@code SlotCalculationService} resolves the effective day, loads the day's bookings and generates
     * candidates entirely in {@link TimeZones#KYIV}).
     *
     * <p>Previously {@code startsAt.toLocalDate()}, i.e. the date in whatever offset the CLIENT sent.
     * A start just after Kyiv midnight submitted as a UTC offset ({@code 2026-08-18T00:30+03:00} sent as
     * {@code 2026-08-17T21:30Z}) therefore queried the PREVIOUS day's slot list and could never match —
     * a spurious 409 (backlog: {@code docs/backend-phases/backlog.md:334}). Fixed here rather than left
     * open because this commit multiplies the guard's call sites from one to four.
     */
    private static LocalDate kyivDate(OffsetDateTime startsAt) {
        return startsAt.atZoneSameInstant(TimeZones.KYIV).toLocalDate();
    }

    /**
     * Compared by {@link OffsetDateTime#isEqual} on the slot start INSTANT, so the client's chosen
     * offset/zone representation is irrelevant. A non-matching start is a {@code 409 "Slot not
     * available"} — the identical status and message the create-path overlap check and the GIST
     * {@code no_overlapping_bookings} backstop already return for an unbookable time, so a rejected
     * create surfaces one coherent conflict rather than leaking WHICH rule rejected it.
     */
    private static void assertMatches(List<AvailableSlotResponse> slots, OffsetDateTime startsAt) {
        boolean onSchedule = slots.stream()
                .anyMatch(slot -> slot.startsAt().toOffsetDateTime().isEqual(startsAt));
        if (!onSchedule) {
            throw new BusinessException(HttpStatus.CONFLICT, "Slot not available");
        }
    }
}
