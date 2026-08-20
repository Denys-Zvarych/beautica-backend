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

    /**
     * The identical status and message the create-path overlap check and the GIST
     * {@code no_overlapping_bookings} backstop already return for an unbookable time, so a rejected
     * create surfaces one coherent conflict rather than leaking WHICH rule rejected it. Named once so
     * the list-matching and existence-checking arms cannot drift apart.
     */
    private static final String SLOT_NOT_AVAILABLE = "Slot not available";

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
     * <b>STAFF create-path counterpart of {@link #assertStartsOnAvailableSlot}</b> (Phase 22.2) —
     * identical oracle, identical comparison, identical 409, evaluated at the STAFF lead-time floor
     * (minimum lead 0) instead of the client one (15 min).
     *
     * <p>The floor is the ONLY difference. Working hours, day-offs, custom-hours gaps, EXPLICIT_TIMES
     * days, master/salon liveness and existing-booking occupancy are resolved by the same
     * {@code computeAvailableSlots} core, so a staff booking can never be accepted on a day or in a
     * gap a client booking would be refused — and vice versa, except inside the 15 minutes the
     * locked walk-in rule deliberately opens up.
     *
     * <p><b>Asks {@link SlotCalculationService#isStaffSlotAvailable}, not
     * {@link SlotCalculationService#getStaffAvailableSlots}</b> (perf LOW, 2026-08-18). The question
     * is a boolean and the answer was a whole day's worth of {@code AvailableSlotResponse}, each
     * holding two {@code ZonedDateTime}s, scanned once and discarded. ({@code SLOT_STEP} is 30
     * minutes and a day's work intervals are disjoint, so a Kyiv day holds at most 48 grid positions
     * in total; a realistic 9-12h working day yields 17-24.) The client path can
     * afford to materialise that list because the {@code available-slots} cache amortises it across
     * requests; the staff list is deliberately uncached, so this one paid the full cost on every
     * create with zero reuse. {@code isStaffSlotAvailable} runs the identical pipeline and applies
     * the identical start comparison inside the per-interval walk, so the verdict is unchanged — the
     * equivalence is pinned by {@code BookingAvailabilityAgreementIT} case 19.
     */
    static void assertStaffStartsOnAvailableSlot(
            SlotCalculationService slotCalculationService, UUID masterId, UUID masterServiceId,
            MasterServiceAssignment preloaded, OffsetDateTime startsAt) {
        if (!slotCalculationService.isStaffSlotAvailable(
                masterId, kyivDate(startsAt), masterServiceId, preloaded, startsAt)) {
            throw new BusinessException(HttpStatus.CONFLICT, SLOT_NOT_AVAILABLE);
        }
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
     * <b>STAFF create-path counterpart of {@link #assertVisitStartsOnAvailableSlot}</b> (Phase 22.10) —
     * identical oracle, identical comparison, identical 409, evaluated at the STAFF lead-time floor
     * (minimum lead 0) instead of the client one (15 min). The floor is the ONLY difference, exactly as
     * it is the only difference between {@link #assertStartsOnAvailableSlot} and
     * {@link #assertStaffStartsOnAvailableSlot}.
     *
     * <p><b>Whole-chain, never N per-item checks (D2).</b> Items 2..N of a chained visit do not start on
     * the {@code SLOT_STEP} grid at all — {@code VisitPlanner} chains each item to begin when the
     * previous one ends, so a per-item check on item 2+ would reject essentially every legal
     * multi-service walk-in. The chain is contiguous by construction at create time, so pinning the
     * FIRST start against a block sized to the Σ of the ordered assignments' effective durations pins
     * the whole block — the same reasoning {@link #assertVisitStartsOnAvailableSlot} already applies at
     * the client floor, mirrored here at the staff floor. A per-item check would also wrongly ACCEPT a
     * chain whose first service fits alone but whose tail runs past the working window.
     *
     * <p>Asks {@link SlotCalculationService#isStaffVisitSlotAvailable}, not a materialised list, for the
     * same reason {@link #assertStaffStartsOnAvailableSlot} asks {@code isStaffSlotAvailable}: the staff
     * list is deliberately uncached, so a chained visit is *more* expensive to materialise, not less.
     *
     * @param preloaded the PARALLEL assignment list {@code VisitPlanner#planChainedItems} already
     *                  resolved (same size, same order as {@code masterServiceIds}) — see
     *                  {@link #assertVisitStartsOnAvailableSlot}
     */
    static void assertStaffVisitStartsOnAvailableSlot(
            SlotCalculationService slotCalculationService, UUID masterId, List<UUID> masterServiceIds,
            List<MasterServiceAssignment> preloaded, OffsetDateTime startsAt) {
        if (!slotCalculationService.isStaffVisitSlotAvailable(
                masterId, kyivDate(startsAt), masterServiceIds, preloaded, startsAt)) {
            throw new BusinessException(HttpStatus.CONFLICT, SLOT_NOT_AVAILABLE);
        }
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
            throw new BusinessException(HttpStatus.CONFLICT, SLOT_NOT_AVAILABLE);
        }
    }
}
