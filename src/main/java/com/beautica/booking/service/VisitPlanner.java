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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resolves and chains the N services of a single-visit booking into contiguous, per-item priced/duration
 * windows AT CREATE TIME — the ONE piece of the visit-create machinery shared verbatim by BOTH the
 * authenticated APP path ({@link AppointmentService}) and the guest LINK path
 * ({@link GuestBookingService}, BE-7). "Contiguous" here scopes ONLY to {@link #planChainedItems} (the
 * create path, below) — {@link #replanFromNewStart} (the whole-visit reschedule path) never calls
 * {@link #assertContiguous}, and per-item moves (phase 30.1's relaxed contiguity —
 * {@code AppointmentTransitionService#rescheduleAppointmentItem}) bypass this class entirely, moving
 * exactly one child row directly. A visit's items are therefore contiguous only at the moment they are
 * created; the invariant is NOT re-checked or maintained afterwards.
 *
 * <p>Extracted (BE-7) so the two create paths cannot drift on the parts that MUST stay identical:
 * per-item price/duration freeze (override beats base), the D4 buffer policy (each service's own
 * {@code bufferMinutesAfter} applied after it, so the summed block equals the slot BE-2 offered), the
 * {@link SlotCalculationService#MAX_TOTAL_DURATION_MINUTES} Σ-cap and the
 * {@link SlotCalculationService#MAX_SERVICES_PER_VISIT} list-size guard, and the CREATE-TIME contiguity
 * invariant (never relaxed for {@link #planChainedItems} itself — only the visit's post-creation
 * lifetime, per phase 30.1, allows gaps). Only the parts that legitimately differ between the two paths
 * — the advisory-lock ordering (APP takes the client lock first, guest takes only the master lock), the
 * idempotency/client-conflict checks (APP only), and the header shape (APP vs LINK) — live in the two
 * callers.
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
     *
     * <p><b>ONE resolution round-trip, not N</b> (perf LOW, 2026-08-22): the assignments are
     * batch-loaded by {@link #resolveAssignments} before the chaining loop, which then reads them
     * from the map in {@code serviceIds}' own order. See that method for how order, duplicates and
     * the uniform 404 are each preserved bit-for-bit.
     */
    List<PlannedItem> planChainedItems(Master master, List<UUID> serviceIds, OffsetDateTime firstStart) {
        assertServiceIds(serviceIds);

        Map<UUID, MasterServiceAssignment> assignments = resolveAssignments(master, serviceIds);

        List<PlannedItem> items = new ArrayList<>(serviceIds.size());
        OffsetDateTime cursor = firstStart;
        long totalMinutes = 0;
        // Iterates serviceIds, NOT the map: list ORDER drives chain sequencing, and a repeated id
        // legitimately produces its own chain item on each occurrence (locked decision) — both of
        // which a walk over the de-duplicated map would silently destroy.
        for (UUID masterServiceId : serviceIds) {
            MasterServiceAssignment msa = assignments.get(masterServiceId);

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
     * Batch-resolves every DISTINCT {@code serviceId} to its active, master-scoped assignment in ONE
     * round-trip, keyed by assignment id (perf LOW, 2026-08-22).
     *
     * <p>Replaces the per-iteration {@code findByMasterIdAndIdWithGraph} this class ran inside its
     * chaining loop: that cost N sequential single-row SELECTs (N bounded at
     * {@link SlotCalculationService#MAX_SERVICES_PER_VISIT}) and, because duplicate ids are legal
     * input, issued ten byte-identical statements for the same service booked ten times.
     *
     * <h4>The three behaviours this MUST NOT change, and how each is held</h4>
     * <ul>
     *   <li><b>Order</b> — none is expected of the returned map. The caller keeps walking
     *       {@code serviceIds} itself and looks each position up here, so list order still drives
     *       chain sequencing and {@link #assignmentsOf}'s parallel-index contract still holds.</li>
     *   <li><b>Duplicates</b> — de-duplicated for the QUERY only. The same assignment instance is
     *       returned for every occurrence, and each occurrence still produces its own
     *       {@code PlannedItem} with its own window, exactly as N separate lookups did.</li>
     *   <li><b>Uniform 404</b> — the {@code isActive} filter is applied to the batch result with the
     *       identical semantics, and a size shortfall raises the SAME
     *       {@code NotFoundException("Master service not found")}. It deliberately does not name the
     *       offending id: unknown, foreign (another master's) and inactive must stay
     *       indistinguishable, or the message becomes an enumeration oracle over other providers'
     *       catalogues. The shortfall check therefore never reports WHICH id was missing.</li>
     * </ul>
     *
     * <p>The 404 also still precedes the &Sigma;-duration cap, because this runs before the loop that
     * accumulates {@code totalMinutes} — a request that is both over-cap and names an unknown
     * service answers 404, as it did before.
     */
    private Map<UUID, MasterServiceAssignment> resolveAssignments(Master master, List<UUID> serviceIds) {
        Set<UUID> distinctIds = new LinkedHashSet<>(serviceIds);
        // A null element can never resolve to an assignment; the per-id finder answered 404 for it,
        // so short-circuit rather than let Set.copyOf/IN-list turn it into a 500.
        if (distinctIds.remove(null)) {
            throw new NotFoundException("Master service not found");
        }

        Map<UUID, MasterServiceAssignment> byId = masterServiceRepository
                .findByMasterIdAndIdInWithGraph(master.getId(), distinctIds)
                .stream()
                .filter(MasterServiceAssignment::isActive)
                .collect(Collectors.toMap(MasterServiceAssignment::getId, Function.identity()));

        if (byId.size() != distinctIds.size()) {
            throw new NotFoundException("Master service not found");
        }
        return byId;
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

    /**
     * The planned items' already-JOIN-FETCHed assignments, PARALLEL to the {@code serviceIds} list that
     * produced them ({@link #planChainedItems} resolves in list order, so index {@code i} is service
     * {@code i}'s assignment) — the shape
     * {@code SlotCalculationService#getAvailableSlots(UUID, java.time.LocalDate, List, List)} accepts as
     * {@code preloaded}.
     *
     * <p>Lives here, not on either caller, so the APP ({@code AppointmentService}) and LINK
     * ({@code GuestBookingService}) visit-create paths hand the schedule-fit gate the identical list —
     * the same "the two create paths cannot drift" rule this whole class exists for (Perf MEDIUM,
     * 2026-08-11).
     */
    static List<MasterServiceAssignment> assignmentsOf(List<PlannedItem> items) {
        return items.stream().map(PlannedItem::masterService).toList();
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
