package com.beautica.booking.service;

import com.beautica.booking.dto.BookableMasterResponse;
import com.beautica.common.TimeZones;
import com.beautica.common.exception.NotFoundException;
import com.beautica.master.service.MasterScheduleService;
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
 * and {@code ServiceCatalogService} (the service/assignment CRUD) both stop at
 * "{@code is_active} + active assignment" — neither checks whether the master has any usable
 * schedule. That gap let a master with zero weekly schedules (e.g. a SALON_OWNER's
 * auto-created own-master row, which the platform never prompts to configure hours) appear as a
 * selectable booking target whose calendar then shows every date disabled. This service is the
 * single place that closes that gap for the booking-selection flow specifically — the gate is
 * deliberately NOT added to the roster or catalog reads, which serve display purposes where an
 * unscheduled master is still a legitimate listing.
 *
 * <p><b>Schedule-gate strategy: reuse, not a parallel EXISTS query.</b> A candidate is
 * "bookable" only if {@link MasterScheduleService#hasUsableSchedule} — which resolves the exact
 * same override/template/gap precedence, {@code EXPLICIT_TIMES} handling, and {@code valid_to}
 * expiry as the client calendar's {@link MasterScheduleService#getClientWorkingDays} (Phase
 * 15.11), just without materializing the full per-date projection first — reports at least one
 * working day in the same near-term horizon a client would ever actually see. Reusing the
 * resolver (rather than hand-rolling a repository-level {@code EXISTS} over
 * {@code weekly_schedules}/{@code working_intervals}) is a deliberate correctness-first choice:
 * any future change to that precedence only has to be correct in one place, and this list can
 * never drift from what the calendar actually enables. Salon master rosters are small (a handful
 * of candidates per salon, not caller-controlled in size), so resolving each candidate
 * individually is an acceptable cost — {@link MasterScheduleService#hasUsableSchedule} both
 * short-circuits the per-candidate day-walk at the first working day AND is cached
 * ({@code master-usable-schedule}, 60s TTL, {@code sync=true}), mirroring
 * {@code master-working-days}.
 *
 * <p><b>DoS posture (security follow-up).</b> This endpoint is {@code permitAll} (unauthenticated
 * browsing before a client commits to signing up — see {@code SecurityConfig}), whereas
 * {@code GET /masters/{masterId}/working-days} — the calendar endpoint reading the same resolver
 * family — requires {@code isAuthenticated()}. That is a real, structural difference, but it does
 * not create a disproportionate amplification surface: (1) the candidate roster size per request
 * is bounded by how many masters a single salon actually assigns to a single service (small,
 * not attacker-supplied), so a caller cannot inflate the per-request fan-out; (2) the salon and
 * service ids needed to call this endpoint are already discoverable through other {@code permitAll}
 * catalog reads ({@code GET /salons/{salonId}}, {@code GET /salons/{salonId}/services}, public
 * search), so this endpoint grants no new enumeration capability; (3) the short-circuit above
 * means the common case (a master with a normal near-term schedule) resolves in a handful of
 * date-folds rather than the full 181-day walk; and (4) the {@code master-usable-schedule} cache
 * gives it the identical bounded, {@code sync=true} caching posture as the authenticated calendar
 * endpoint, so repeated probing of the same master within the 60s TTL is a cache hit, not a
 * repeated DB resolution. No additional rate limiting was added — Bucket4j is reserved for
 * {@code /auth/*} and blanket-applying it here would throttle legitimate unauthenticated browsing.
 *
 * <p>The horizon mirrors {@link BookingStartsAtValidator#MAX_DAYS_AHEAD} (180 days) — the
 * maximum lead time a booking can ever be created for, so checking schedule usability further
 * out than that would find "working days" a client could never actually book against.
 */
@Service
public class BookingMasterService {

    private final SalonRepository salonRepository;
    private final ServiceRepository serviceRepository;
    private final MasterServiceRepository masterServiceRepository;
    private final MasterScheduleService masterScheduleService;
    private final Clock kyivClock;

    public BookingMasterService(
            SalonRepository salonRepository,
            ServiceRepository serviceRepository,
            MasterServiceRepository masterServiceRepository,
            MasterScheduleService masterScheduleService,
            Clock clock) {
        this.salonRepository = salonRepository;
        this.serviceRepository = serviceRepository;
        this.masterServiceRepository = masterServiceRepository;
        this.masterScheduleService = masterScheduleService;
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
        LocalDate to = from.plusDays(BookingStartsAtValidator.MAX_DAYS_AHEAD);

        return candidates.stream()
                .filter(assignment ->
                        masterScheduleService.hasUsableSchedule(assignment.getMaster().getId(), from, to))
                .map(BookableMasterResponse::from)
                .toList();
    }
}
