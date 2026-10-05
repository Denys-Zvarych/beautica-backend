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
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Resolves which of a salon's masters are actually bookable for a given service (Phase 23.x —
 * {@code GET /salons/{salonId}/services/{serviceDefId}/masters}).
 *
 * <p><b>Why this exists.</b> {@code ServiceCatalogService} (the service/assignment CRUD) stops at
 * "{@code is_active} + active assignment" — it never checks whether the master can actually be
 * booked. That gap let a master with no usable schedule, or one whose calendar is fully booked out
 * across the entire horizon, appear as a selectable booking target whose slot picker then shows
 * nothing free. This service closes that gap for the booking-selection flow AND for the public
 * salon roster ({@code GET /salons/{id}/masters} and {@code GET /masters/by-salon/{id}}, both routed
 * through {@code MasterService#getMastersByPage} → {@link #getBookableMasterIds}) — an auto-enrolled
 * owner-master or an invited master with no services or no working hours is not listed to clients.
 * The management roster {@code SalonService#getSalonStaff} («Команда») stays deliberately unfiltered.
 *
 * <p><b>Bookability gate: the single shared free-slot verdict.</b> A candidate is "bookable" only
 * if {@link SlotCalculationService#hasBookableFutureSlot} — active assignment, active master, a
 * usable schedule in the booking window, AND ≥1 FREE FUTURE slot (existing CONFIRMED
 * bookings subtracted; slot start ≥ now + {@link BookingWindow#MIN_MINUTES_AHEAD}). This
 * is the exact same verdict the salon catalogue uses
 * ({@code ServiceCatalogService#getSalonServiceCatalog} via
 * {@link SlotCalculationService#filterBookableAssignmentsBatch}), computed from the exact same
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

    /**
     * Cache backing {@link #getBookableAssignmentIds}; registered in {@code CacheConfig}, evicted by
     * master-prefix sweep ({@code SlotCalculationService}, {@code MasterScheduleService}) and by
     * {@code ServiceCatalogService} on assignment writes.
     */
    public static final String BOOKABLE_ASSIGNMENTS_CACHE = "master-bookable-assignments";

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
     * Ids of every master in {@code salonId} that is bookable for AT LEAST ONE of the salon's
     * services — the public-roster gate behind {@code MasterService#getMastersByPage}.
     *
     * <p><b>One verdict, not a second predicate.</b> Candidates are the exact
     * {@link MasterServiceRepository#findBookableAssignmentsBySalon} set the salon catalogue loads,
     * grouped by master and passed through the same single
     * {@link SlotCalculationService#filterBookableAssignmentsBatch} call
     * ({@code ServiceCatalogService#bookableDefinitions}) — so a master is listed iff at least one of
     * their services survives into {@code GET /salons/{id}/services}. A master with no assignments
     * never enters the candidate set (zero cost); one with assignments but no working hours /
     * no free slot is gated out by the batch.
     *
     * <p><b>Cost.</b> One assignment query + the batch's fixed two statements (Phase 315), never
     * per master. Cached in {@code salon-bookable-masters} keyed on {@code salonId} (60-sec TTL,
     * {@code sync=true} — §F-7, this backs two {@code permitAll} reads) and evicted afterCommit by
     * {@code SalonCatalogCacheEvictor} alongside {@code salon-service-catalog}: every write that can
     * flip the catalogue verdict (booking, schedule, assignment, master deactivation) flips this one,
     * so it reuses that eviction wiring rather than adding a parallel one. Only ids are cached; the
     * roster page itself (names, ratings, avatars) is always read live.
     */
    @Transactional(readOnly = true)
    @Cacheable(value = "salon-bookable-masters", key = "#salonId", sync = true)
    public Set<UUID> getBookableMasterIds(UUID salonId) {
        return mastersWithABookableAssignment(
                bookableAssignmentsByMaster(masterServiceRepository.findBookableAssignmentsBySalon(salonId)));
    }

    /**
     * Ids of {@code masterId}'s {@code master_services} rows that pass the strict free-slot verdict —
     * the client view of {@code GET /masters/{id}/services} ({@code MasterServiceBookabilityFilter})
     * and, via "non-empty", the {@code bookable} flag of {@code GET /masters/{id}}
     * ({@code MasterService#isBookable}). Same verdict and loading strategy as
     * {@link #getBookableMasterIds(UUID)}: candidates from
     * {@link MasterServiceRepository#findBookableAssignmentsByMasterIds} (the master-scoped sibling
     * of the salon finder, same ownership rule) through the single
     * {@link SlotCalculationService#filterBookableAssignmentsBatch} call.
     *
     * <p><b>Cached (perf + security audit 2026-10-05, finding 2).</b> Both callers back
     * {@code permitAll} reads, and the batch walks up to the 180-day horizon for a master with no
     * free day — uncached, a crawler sweeping master ids drove that walk once per request. Cached in
     * {@value #BOOKABLE_ASSIGNMENTS_CACHE} keyed {@code [masterId]} (60-sec TTL, {@code sync=true} —
     * §F-7). The key is a one-element SpEL list on purpose: every per-master slot cache is keyed by a
     * list whose FIRST element is the master id, so this cache rides the SAME afterCommit by-master
     * sweeps — {@code SlotCalculationService#evictMasterAvailabilityCaches} (every booking
     * create/cancel/transition, unassign, definition deactivation, master (de)activation) and
     * {@code MasterScheduleService}'s schedule-write sweep — with no parallel eviction wiring; the
     * assignment writes that add a bookable row evict the key directly with
     * {@code masterServices} ({@code ServiceCatalogService#evictMasterServicesCache}).
     */
    @Transactional(readOnly = true)
    @Cacheable(value = BOOKABLE_ASSIGNMENTS_CACHE, key = "{#masterId}", sync = true)
    public Set<UUID> getBookableAssignmentIds(UUID masterId) {
        return bookableAssignmentsByMaster(
                masterServiceRepository.findBookableAssignmentsByMasterIds(List.of(masterId)))
                .values().stream()
                .flatMap(List::stream)
                .map(MasterServiceAssignment::getId)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Groups candidates by master and applies the ONE shared free-slot batch gate. */
    private Map<UUID, List<MasterServiceAssignment>> bookableAssignmentsByMaster(
            List<MasterServiceAssignment> candidates) {
        Map<UUID, List<MasterServiceAssignment>> candidatesByMaster = candidates.stream()
                .collect(Collectors.groupingBy(a -> a.getMaster().getId()));
        if (candidatesByMaster.isEmpty()) {
            return Map.of();
        }
        return slotCalculationService.filterBookableAssignmentsBatch(candidatesByMaster);
    }

    private static Set<UUID> mastersWithABookableAssignment(
            Map<UUID, List<MasterServiceAssignment>> gated) {
        return gated.entrySet().stream()
                .filter(entry -> !entry.getValue().isEmpty())
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
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
