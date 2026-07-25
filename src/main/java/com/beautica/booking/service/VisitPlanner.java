package com.beautica.booking.service;

import com.beautica.booking.dto.BookingPriceRange;
import com.beautica.booking.entity.Booking;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.master.entity.Master;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.repository.MasterServiceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Resolves and chains the N services of a single-visit booking into contiguous, per-item priced/duration
 * windows — the ONE piece of the visit-create machinery shared verbatim by BOTH the authenticated APP
 * path ({@link AppointmentService}) and the guest LINK path ({@link GuestBookingService}, BE-7).
 *
 * <p>Extracted (BE-7) so the two create paths cannot drift on the parts that MUST stay identical:
 * per-item price/duration freeze (override beats base), the D4 buffer policy (each service's own
 * {@code bufferMinutesAfter} applied after it, so the summed block equals the slot BE-2 offered), the
 * {@link SlotCalculationService#MAX_TOTAL_DURATION_MINUTES} Σ-cap and the
 * {@link SlotCalculationService#MAX_SERVICES_PER_VISIT} list-size guard, and the contiguity invariant.
 * Only the parts that legitimately differ between the two paths — the advisory-lock ordering (APP takes
 * the client lock first, guest takes only the master lock), the idempotency/client-conflict checks (APP
 * only), and the header shape (APP vs LINK) — live in the two callers.
 *
 * <p>Deliberately clock-free and lock-free: it performs only the master-scoped service resolution and
 * pure arithmetic. Lead-time / max-window validation ({@code BookingStartsAtValidator}) and all
 * concurrency guards stay in the callers, exactly where they were before the extraction.
 */
@Component
@RequiredArgsConstructor
class VisitPlanner {

    private final MasterServiceRepository masterServiceRepository;

    /**
     * Resolves each service in list order and chains their windows back-to-back, freezing the
     * per-item price/duration snapshot. Item 0 starts at {@code firstStart}; item {@code i} starts
     * when item {@code i-1} ends, where each item's length is its effective duration (override beats
     * base) PLUS its own {@code bufferMinutesAfter} — identical to BE-2's {@code effectiveDuration},
     * so the summed block equals the slot the client was offered. Enforces the
     * {@link SlotCalculationService#MAX_SERVICES_PER_VISIT} list-size guard and the
     * {@link SlotCalculationService#MAX_TOTAL_DURATION_MINUTES} Σ-duration ceiling.
     *
     * <p>Duplicates are allowed verbatim (locked decision): the block sums repeats, staying consistent
     * with BE-2 availability. An unknown, foreign OR inactive {@code masterServiceId} answers 404
     * uniformly — the master-scoped finder contract BE-2 uses.
     */
    List<PlannedItem> planChainedItems(Master master, List<UUID> serviceIds, OffsetDateTime firstStart) {
        assertServiceIds(serviceIds);

        List<PlannedItem> items = new ArrayList<>(serviceIds.size());
        OffsetDateTime cursor = firstStart;
        long totalMinutes = 0;
        for (UUID masterServiceId : serviceIds) {
            MasterServiceAssignment msa = masterServiceRepository
                    .findByMasterIdAndIdWithGraph(master.getId(), masterServiceId)
                    .filter(MasterServiceAssignment::isActive)
                    .orElseThrow(() -> new NotFoundException("Master service not found"));

            int duration = msa.getDurationOverrideMinutes() != null
                    ? msa.getDurationOverrideMinutes()
                    : msa.getServiceDefinition().getBaseDurationMinutes();
            int buffer = msa.getServiceDefinition().getBufferMinutesAfter();
            BigDecimal price = msa.getPriceOverride() != null
                    ? msa.getPriceOverride()
                    : msa.getServiceDefinition().getBasePrice();

            OffsetDateTime start = cursor;
            OffsetDateTime end = start.plusMinutes((long) duration + buffer);
            items.add(new PlannedItem(
                    msa, start, end, duration, buffer, price, BookingPriceRange.resolveCeiling(msa)));

            totalMinutes += (long) duration + buffer;
            cursor = end;
        }
        if (totalMinutes > SlotCalculationService.MAX_TOTAL_DURATION_MINUTES) {
            throw new BusinessException("total service duration exceeds maximum allowed");
        }
        assertContiguous(items);
        return items;
    }

    /**
     * Defensive belt behind the DTO's {@code @NotEmpty}/{@code @Size} — the planner must never trust an
     * empty or unbounded list (mirrors {@code SlotCalculationService#assertServiceIds}): an empty list
     * would NPE on {@code items.get(0)} downstream; an unbounded one is a slot-calculator amplifier and
     * one master-service lookup per id.
     */
    private void assertServiceIds(List<UUID> serviceIds) {
        if (serviceIds == null || serviceIds.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "At least one service is required");
        }
        if (serviceIds.size() > SlotCalculationService.MAX_SERVICES_PER_VISIT) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "At most " + SlotCalculationService.MAX_SERVICES_PER_VISIT
                            + " services can be booked in a single visit");
        }
    }

    /**
     * Defensive invariant guard (never client-triggered — the chain is built contiguous above): each
     * item is positive-width and each item starts exactly where the previous one ended, so no two
     * chained items can ever overlap or leave a negative gap.
     */
    private void assertContiguous(List<PlannedItem> items) {
        for (int i = 0; i < items.size(); i++) {
            PlannedItem item = items.get(i);
            if (!item.endsAt().isAfter(item.startsAt())) {
                throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "Appointment item has non-positive duration");
            }
            if (i > 0 && !item.startsAt().isEqual(items.get(i - 1).endsAt())) {
                throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "Chained appointment items are not contiguous");
            }
        }
    }

    /**
     * Re-lays-out an ALREADY-BOOKED visit's chained items from a new first start, preserving
     * each item's frozen duration/buffer snapshot (BE-4 visit reschedule) — the timing-only twin
     * of {@link #planChainedItems}.
     *
     * <p>Reuses the identical back-to-back chaining formula this class uses for creation
     * ({@code start[i] = start[i-1].endsAt}, {@code end[i] = start[i] + duration[i] + buffer[i]})
     * and the same {@link SlotCalculationService#MAX_TOTAL_DURATION_MINUTES} &Sigma;-cap, but
     * resolves NOTHING from {@code master_services}: {@code priceAtBooking} /
     * {@code durationMinutesAtBooking} / {@code bufferMinutesAtBooking} stay frozen at each item's
     * original booking-time values (mirrors {@code BookingService#rescheduleBooking}, which
     * freezes the same two fields on the single-service path) — only the clock position of the
     * whole block moves. A catalogue price/duration change since booking can therefore never leak
     * into an existing visit via a reschedule.
     *
     * <p>{@code orderedItems} must already be ordered by {@code startsAt} ascending — the exact
     * shape {@code BookingRepository#findByAppointmentIdWithGraph} returns — and the returned list
     * is parallel (same size, same order): index {@code i} is item {@code i}'s new window.
     */
    List<PlannedWindow> replanFromNewStart(List<Booking> orderedItems, OffsetDateTime newFirstStart) {
        List<PlannedWindow> windows = new ArrayList<>(orderedItems.size());
        OffsetDateTime cursor = newFirstStart;
        long totalMinutes = 0;
        for (Booking item : orderedItems) {
            int duration = item.getDurationMinutesAtBooking();
            int buffer = item.getBufferMinutesAtBooking();
            OffsetDateTime start = cursor;
            OffsetDateTime end = start.plusMinutes((long) duration + buffer);
            windows.add(new PlannedWindow(start, end));
            totalMinutes += (long) duration + buffer;
            cursor = end;
        }
        if (totalMinutes > SlotCalculationService.MAX_TOTAL_DURATION_MINUTES) {
            throw new BusinessException("total service duration exceeds maximum allowed");
        }
        return windows;
    }

    /** One resolved, priced service line ready to become a chained {@code Booking} row. */
    record PlannedItem(
            MasterServiceAssignment masterService,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            int duration,
            int buffer,
            BigDecimal price,
            BigDecimal priceMax) {}

    /** One item's re-laid-out window — parallel-indexed to the caller's ordered item list. */
    record PlannedWindow(OffsetDateTime startsAt, OffsetDateTime endsAt) {}
}
