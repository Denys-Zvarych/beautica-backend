package com.beautica.booking.service;

import com.beautica.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

import java.time.OffsetDateTime;
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
     * @param slotCalculationService the CALLER's own injected instance — never constructed here
     */
    static void assertStartsOnAvailableSlot(
            SlotCalculationService slotCalculationService, UUID masterId, UUID masterServiceId,
            OffsetDateTime startsAt) {
        boolean onSchedule = slotCalculationService
                .getAvailableSlots(masterId, startsAt.toLocalDate(), masterServiceId)
                .stream()
                .anyMatch(slot -> slot.startsAt().toOffsetDateTime().isEqual(startsAt));
        if (!onSchedule) {
            throw new BusinessException(HttpStatus.CONFLICT, "Slot not available");
        }
    }
}
