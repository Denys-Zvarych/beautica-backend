package com.beautica.booking.service;

import com.beautica.booking.dto.BookableMasterResponse;
import com.beautica.common.BookingWindow;
import com.beautica.common.TimeZones;
import com.beautica.common.exception.NotFoundException;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.OwnerType;
import com.beautica.service.entity.ServiceDefinition;
import com.beautica.service.repository.MasterServiceRepository;
import com.beautica.service.repository.ServiceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Resolves which of a salon's masters are actually bookable for a given service (Phase 23.x —
 * {@code GET /salons/{salonId}/services/{serviceDefId}/masters}).
 *
 * <p><b>Why this exists.</b> {@code SalonService#getMastersBySalon} (the salon-profile roster)
 * and {@code ServiceCatalogService} (the service/assignment CRUD) both stopped at
 * "{@code is_active} + active assignment" — neither checked whether the master could actually be
 * booked. That gap let a master with no usable schedule, or one whose calendar is fully booked out
 * across the entire horizon, appear as a selectable booking target whose slot picker then shows
 * nothing free. This service closes that gap for the booking-selection flow.
 *
 * <p><b>Bookability gate: the single shared free-slot verdict.</b> A candidate is "bookable" only
 * if {@link SlotCalculationService#hasBookableFutureSlot} — active assignment, active master, a
 * usable schedule in the booking window, AND ≥1 FREE FUTURE slot (existing PENDING/CONFIRMED
 * bookings subtracted; slot start ≥ now + {@link BookingWindow#MIN_MINUTES_AHEAD}). This
 * is the exact same verdict the salon catalogue uses
 * ({@code ServiceCatalogService#getSalonServiceCatalog} via
 * {@link SlotCalculationService#filterBookableAssignments}), computed from the exact same
 * effective-day resolver and {@code TimeSlotCalculator} subtraction that
 * {@link SlotCalculationService#getAvailableSlots} exposes — so the master list, the catalogue, and
 * the slot picker can never disagree on whether a master is bookable. Salon master rosters are small
 * (a handful of candidates per salon, not caller-controlled), so resolving each candidate is
 * acceptable — {@link SlotCalculationService#hasBookableFutureSlot} short-circuits the day-walk at
 * the first free slot AND is cached ({@code master-service-bookable}, 60s TTL, {@code sync=true}),
 * evicted by master prefix on every schedule write and every booking write.
 *
 * <p><b>DoS posture (security follow-up).</b> This endpoint is {@code permitAll} (unauthenticated
 * browsing before a client commits to signing up — see {@code SecurityConfig}). It does not create a
 * disproportionate amplification surface: (1) the candidate roster size per request is bounded by how
 * many masters a single salon assigns to a single service (small, not attacker-supplied); (2) the
 * salon and service ids needed are already discoverable through other {@code permitAll} catalog
 * reads, so this grants no new enumeration capability; (3) the short-circuit means the common case
 * (a master with near-term free slots) resolves in a handful of date-folds rather than the full
 * 181-day walk; and (4) the {@code master-service-bookable} cache gives repeated probing of the same
 * master within the 60s TTL a cache hit, not a repeated DB resolution. No additional rate limiting
 * was added — Bucket4j is reserved for {@code /auth/*}.
 *
 * <p>The horizon mirrors {@link BookingWindow#MAX_DAYS_AHEAD} (180 days) — the maximum
 * lead time a booking can ever be created for, so checking bookability further out would find slots a
 * client could never actually book against.
 */
@Service
public class BookingMasterService {

    private final SalonRepository salonRepository;
    private final ServiceRepository serviceRepository;
    private final MasterServiceRepository masterServiceRepository;
    private final SlotCalculationService slotCalculationService;
    private final Clock kyivClock;

    public BookingMasterService(
            SalonRepository salonRepository,
            ServiceRepository serviceRepository,
            MasterServiceRepository masterServiceRepository,
            SlotCalculationService slotCalculationService,
            Clock clock) {
        this.salonRepository = salonRepository;
        this.serviceRepository = serviceRepository;
        this.masterServiceRepository = masterServiceRepository;
        this.slotCalculationService = slotCalculationService;
        this.kyivClock = clock.withZone(TimeZones.KYIV);
    }

    /**
     * Returns the masters bookable for {@code serviceDefId} within {@code salonId}: active,
     * actively assigned to the service, and schedule-usable (see class Javadoc). An empty result
     * is a valid outcome (200 with {@code []}) — it is NOT a 404; only a missing/inactive salon
     * or a service definition that does not resolve to an active SALON-owned service within
     * {@code salonId} are 404s.
     *
     * @throws NotFoundException if {@code salonId} does not resolve to an active salon, or if
     *                           {@code serviceDefId} does not resolve to an active service
     *                           definition owned by that salon
     */
    @Transactional(readOnly = true)
    public List<BookableMasterResponse> getBookableMasters(UUID salonId, UUID serviceDefId) {
        if (!salonRepository.existsByIdAndIsActiveTrue(salonId)) {
            throw new NotFoundException("Salon not found: " + salonId);
        }

        ServiceDefinition serviceDef = serviceRepository.findById(serviceDefId)
                .orElseThrow(() -> new NotFoundException("Service not found: " + serviceDefId));
        // A wrong-salon or deactivated service resolves to the same 404 as "not found" —
        // distinguishing them would let a caller probe which service ids exist elsewhere
        // (mirrors the existence-oracle avoidance pattern used throughout this codebase).
        if (serviceDef.getOwnerType() != OwnerType.SALON
                || !serviceDef.getOwnerId().equals(salonId)
                || !serviceDef.isActive()) {
            throw new NotFoundException("Service not found: " + serviceDefId);
        }

        List<MasterServiceAssignment> candidates =
                masterServiceRepository.findBookableAssignmentsBySalonAndServiceDef(salonId, serviceDefId);
        if (candidates.isEmpty()) {
            return List.of();
        }

        LocalDate from = LocalDate.now(kyivClock);
        LocalDate to = from.plusDays(BookingWindow.MAX_DAYS_AHEAD);

        return candidates.stream()
                // Pass the already JOIN-FETCHed assignment as `preloaded` so a cache MISS reuses it
                // instead of re-issuing findByMasterIdAndIdWithGraph (Perf #4). It is not part of the
                // cache key, so the cached verdict is shared with entity-less callers.
                .filter(assignment -> slotCalculationService.hasBookableFutureSlot(
                        assignment.getMaster().getId(), assignment.getId(), assignment, from, to))
                .map(BookableMasterResponse::from)
                .toList();
    }
}
