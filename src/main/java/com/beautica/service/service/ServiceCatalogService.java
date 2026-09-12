package com.beautica.service.service;

import com.beautica.booking.repository.BookingRepository;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.DuplicateServiceException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.master.repository.MasterRepository;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.service.dto.AssignServiceToMasterRequest;
import com.beautica.service.dto.BulkCreateServicesRequest;
import com.beautica.service.dto.BulkServiceItemRequest;
import com.beautica.service.dto.CatalogCategoryResponse;
import com.beautica.service.dto.CreateServiceDefinitionRequest;
import com.beautica.service.dto.MasterServiceBand;
import com.beautica.service.dto.MasterServiceResponse;
import com.beautica.service.dto.SalonServiceCatalogResponse;
import com.beautica.service.dto.SalonServiceCategoryGroup;
import com.beautica.service.dto.ServiceDefinitionResponse;
import com.beautica.service.dto.ServicePricing;
import com.beautica.service.dto.PlatformServiceTypeResponse;
import com.beautica.service.dto.ServiceTypeResponse;
import com.beautica.service.dto.SuggestServiceTypeRequest;
import com.beautica.service.dto.UpdateMasterServiceBandRequest;
import com.beautica.service.dto.UpdateServiceDefinitionRequest;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.OwnerType;
import com.beautica.service.entity.PlatformCategory;
import com.beautica.service.entity.PriceType;
import com.beautica.service.entity.ServiceDefinition;
import com.beautica.service.entity.ServiceType;
import com.beautica.service.repository.ActiveDuplicateProjection;
import com.beautica.service.repository.MasterServiceRepository;
import com.beautica.service.repository.PlatformCategoryRepository;
import com.beautica.service.repository.SalonBulkSetupCandidate;
import com.beautica.service.repository.ServiceRepository;
import com.beautica.service.repository.ServiceTypeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;


import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class ServiceCatalogService {

    /**
     * Name of the partial unique index added in V121 that enforces one ACTIVE service per
     * {@code (owner_type, owner_id, service_type_id)}. Matched against the DB's constraint-violation
     * report to translate it into a {@link DuplicateServiceException}.
     */
    private static final String DUPLICATE_SERVICE_INDEX = "ux_service_def_owner_service_type_active";

    /**
     * Page cap for {@link #getSalonMasterServices(UUID, UUID, UUID)} — matches the established,
     * unnamed {@code 200} literal on the sibling reads {@link #getMyServices} and
     * {@link #getMasterServices(UUID)}. Named here only so the boundary check in
     * {@code getSalonMasterServices} has a single source of truth; the sibling methods are out of
     * scope for this constant (Phase 309 audit-fix LOW-1) and keep their own literals.
     */
    private static final int SALON_MASTER_SERVICES_PAGE_CAP = 200;

    private final ServiceRepository serviceRepository;
    private final MasterServiceRepository masterServiceRepository;
    private final SalonRepository salonRepository;
    private final MasterRepository masterRepository;
    private final CatalogCategoryLookup catalogCategoryLookup;
    private final PlatformCategoryRepository platformCategoryRepository;
    private final PlatformCategoryOrderLookup platformCategoryOrderLookup;
    private final ServiceTypeSuggestionService serviceTypeSuggestionService;
    private final ServiceTypeLookup serviceTypeLookup;
    private final ServiceTypeSearchService serviceTypeSearchService;
    private final ServiceTypeRepository serviceTypeRepository;
    private final CacheManager cacheManager;
    // Phase 307 MEDIUM-3 (perf audit) retired this class's only synchronous, direct-call use
    // (evictAvailableSlotsCache/doEvictAvailableSlots) — the off-thread sweep behind
    // evictBookableFutureSlotsCache already covers "available-slots" as a superset
    // (SlotCalculationService#BOOKING_WRITE_CACHES). Left wired rather than removed: deleting the
    // constructor parameter ripples into every @InjectMocks/@SpringBootTest construction site of
    // this class across the test suite for no behavioural gain, out of scope for this fix.
    private final com.beautica.common.cache.MasterCachePrefixEvictor cachePrefixEvictor;
    private final com.beautica.common.security.AuthorizationService authz;
    private final com.beautica.booking.service.SlotCalculationService slotCalculationService;
    private final SalonCatalogCacheEvictor salonCatalogCacheEvictor;
    // Phase 307 D4 — the per-assignment future-CONFIRMED-booking guard on unassignServiceFromMaster.
    // Direct cross-feature repository injection, matching this class's existing MasterRepository/
    // SalonRepository fields above rather than a new booking-service seam (REUSE-FIRST — no new
    // booking-service method exists or is needed for a single COUNT read).
    private final BookingRepository bookingRepository;
    // Phase 307 D4 (anti-bug §G) — never Instant.now()/OffsetDateTime.now() directly; the "now"
    // boundary for the future-booking scan must be pinned through the injected Clock so tests can
    // control it.
    private final Clock clock;

    @Transactional
    public ServiceDefinitionResponse addServiceToSalon(
            UUID salonId,
            CreateServiceDefinitionRequest request) {

        if (!salonRepository.existsById(salonId)) {
            throw new NotFoundException("Salon not found");
        }

        validateCategoryActive(request.category());

        ServiceType serviceType = resolveRequiredServiceType(request.serviceTypeId(), request.category());

        // One extra SELECT on the success path, accepted deliberately so the conflict can carry
        // existingServiceDefId — the lazy "look it up in the catch" alternative needs
        // REQUIRES_NEW (the violation aborts the tx). Rationale in full on the method.
        assertNoActiveDuplicate(OwnerType.SALON, salonId, serviceType, null);

        ServiceDefinition definition = ServiceDefinition.builder()
                .ownerType(OwnerType.SALON)
                .ownerId(salonId)
                .name(resolveCreateName(request.name(), serviceType))
                .description(request.description())
                .category(request.category())
                .baseDurationMinutes(request.baseDurationMinutes())
                .bufferMinutesAfter(request.bufferMinutesAfter())
                .isActive(true)
                .build();

        applyPriceMode(definition, request.priceType(), request.price(), request.priceMin(), request.priceMax());
        definition.setServiceType(serviceType);

        ServiceDefinition saved = persistDefinition(definition);
        // A new SALON-owned definition has no assignment yet (not bookable), but evict the salon
        // catalogue for consistency with the other mutation sites (perf/security #2) — cheap per-key.
        evictSalonCatalogAfterCommit(salonId);
        return ServiceDefinitionResponse.from(saved);
    }

    @Transactional
    public MasterServiceResponse assignServiceToMaster(
            UUID salonId,
            UUID masterId,
            AssignServiceToMasterRequest request) {

        // Type-agnostic: accepts SALON_MASTER, SALON_OWNER, and INDEPENDENT_MASTER rows equally.
        // A SALON_OWNER-type master row has salon_id = the owner's salon, so the salon-membership
        // check at line ~92 resolves true for owner-as-master without any special-casing.
        Master master = masterRepository.findById(masterId)
                .orElseThrow(() -> new NotFoundException("Master not found: " + masterId));

        if (master.getSalon() == null || !master.getSalon().getId().equals(salonId)) {
            throw new ForbiddenException("Access denied");
        }

        ServiceDefinition serviceDef = serviceRepository.findByIdWithServiceType(request.serviceDefId())
                .orElseThrow(() -> new NotFoundException("Service definition not found: " + request.serviceDefId()));

        if (serviceDef.getOwnerType() != OwnerType.SALON || !serviceDef.getOwnerId().equals(salonId)) {
            throw new ForbiddenException("Service definition does not belong to this salon");
        }

        // Phase 313 D1 — a deactivated definition is 404, indistinguishable from a nonexistent
        // one. MUST run after the ownership check above so a caller probing another salon's ids
        // still gets 403, not a 404 that would confirm the id exists. This checks
        // service_definitions.is_active — a different column, on a different table, from the
        // master_services.is_active read a few lines below (Phase 307 D6's reactivation branch);
        // conflating the two breaks MasterServiceUnassignIT case 8.
        if (!serviceDef.isActive()) {
            throw new NotFoundException("Service definition not found: " + request.serviceDefId());
        }

        // Phase 307 D6 — ACTIVE-agnostic lookup, not existsByMasterIdAndServiceDefinitionId:
        // master_services' UNIQUE (master_id, service_def_id) is NOT partial, so an existing
        // INACTIVE row (the master previously unassigned this exact service via
        // unassignServiceFromMaster) must be REACTIVATED here, not treated as "already assigned"
        // (wrong — the caller can plainly see it is not) nor left for a plain INSERT to hit at
        // flush (a 500-flavoured opaque 409, not this endpoint's clean one).
        Optional<MasterServiceAssignment> existingAssignment = masterServiceRepository
                .findByMasterIdAndServiceDefinitionId(masterId, request.serviceDefId());

        MasterServiceAssignment saved;
        if (existingAssignment.isPresent() && existingAssignment.get().isActive()) {
            // Phase 313 D2 — typed, matching the bulk and independent-master paths: same
            // constructor, same GlobalExceptionHandler arm, same 409 DuplicateServiceErrorResponse
            // body, same data.code == DUPLICATE_SERVICE. No parallel mechanism.
            throw new DuplicateServiceException(serviceDef.getName(), serviceDef.getId());
        } else if (existingAssignment.isPresent()) {
            // Phase 312 D1/D2 — the request's own @AssertTrue (MasterServiceBand.isLegal) has
            // already refused a partial or incoherent band before this method runs; write the
            // complete triple together so a reactivated row can never carry a bare
            // price_override, which V165's chk_master_service_price_mode would refuse at flush
            // (the 311/312 merge-unit hazard).
            MasterServiceAssignment existing = existingAssignment.get();
            existing.setActive(true);
            existing.setPriceTypeOverride(request.priceType());
            existing.setPriceOverride(request.priceOverride());
            existing.setPriceMaxOverride(request.priceMax());
            existing.setDurationOverrideMinutes(request.durationOverrideMinutes());
            saved = existing;
        } else {
            MasterServiceAssignment assignment = MasterServiceAssignment.builder()
                    .master(master)
                    .serviceDefinition(serviceDef)
                    .priceTypeOverride(request.priceType())
                    .priceOverride(request.priceOverride())
                    .priceMaxOverride(request.priceMax())
                    .durationOverrideMinutes(request.durationOverrideMinutes())
                    .isActive(true)
                    .build();

            saved = masterServiceRepository.save(assignment);
        }

        // PERF-M2: keep the pre-computed min_effective_price in sync so the
        // search index reflects the new assignment immediately on next cache miss.
        masterRepository.refreshMinEffectivePrice(masterId);

        // Evict after commit so a parallel reader cannot repopulate the cache with
        // the pre-insert DB snapshot between eviction and commit (anti-bug §F).
        evictMasterServicesCache(List.of(masterId));
        // A new assignment can make a previously-unbookable SALON service appear in the catalogue
        // (perf/security #2): evict this salon's catalogue.
        evictSalonCatalogAfterCommit(salonId);

        return MasterServiceResponse.from(saved);
    }

    /**
     * Edits ONE master's OWN price band and/or duration override on ONE assignment (Phase 311) —
     * the only {@code PATCH}/{@code PUT} anywhere on {@code master_services}. Mirrors the
     * assign/unassign pair's URL and lookup: same natural key {@code (masterId, serviceDefId)},
     * same {@link MasterServiceRepository#findByMasterIdAndServiceDefinitionId} finder, same
     * ownership re-checks as {@link #assignServiceToMaster} (D1 — no new repository method).
     *
     * <p><b>D2 — all-or-nothing.</b> {@code request}'s bean validation (its {@code @AssertTrue}
     * methods, calling {@link MasterServiceBand#isLegal}) has already refused a partial or
     * incoherent band before this method runs; this method only decides WHICH of the three
     * mutually-exclusive shapes the request represents:
     * <ul>
     *   <li>{@code clearBand = true} — reverts to Inherited: all three band columns NULL.</li>
     *   <li>Any of {@code priceType}/{@code price}/{@code priceMax} present — the master's own,
     *       already-validated band; all three columns are written together.</li>
     *   <li>Neither — the band is left byte-for-byte untouched (D4's "null means unchanged").</li>
     * </ul>
     * {@code durationOverrideMinutes}/{@code clearDurationOverride} are resolved independently
     * (D2 — duration carries no shape and no ceiling).
     *
     * <p><b>D5 — service-layer defense-in-depth.</b> {@link AuthorizationService
     * #enforceCanEditMasterServiceBand} re-proves the controller's {@code @PreAuthorize} grant
     * before anything is loaded, exactly as {@link #assignServiceToMaster}'s own
     * {@code masterBelongsToSalon} re-check and {@link #deactivateServiceDefinition}'s
     * {@code enforceCanManageServiceDefinition} never trust the SpEL gate alone.
     *
     * <p><b>D10 — bookings are never touched, and never block.</b> Unlike
     * {@link #unassignServiceFromMaster}, there is no future-{@code CONFIRMED}-booking guard: a
     * booking's frozen {@code priceAtBooking}/{@code priceMaxAtBooking} are snapshots the locked
     * product decision forbids re-deriving, so an existing booking is simply irrelevant to this
     * write.
     *
     * <p><b>D11 — eviction is conditional, not blanket.</b> {@code masterServices} and
     * {@code salon-service-catalog} evict unconditionally (any band or duration change can move
     * what those caches serve); {@code available-slots}/bookable-verdict evicts ONLY when the
     * duration actually changed (a band-only edit does not feed slot maths); and
     * {@code masters.min_effective_price} refreshes ONLY when the resolved floor actually changed
     * (a RANGE ceiling moving alone does not). Both conditionals compare the RESOLVED value
     * before/after, not merely "was a field present in the request" — a request that happens to
     * restate the current value must not trigger a needless sweep.
     *
     * @throws NotFoundException  if no ACTIVE assignment exists for {@code (masterId, serviceDefId)}
     * @throws ForbiddenException if the actor cannot manage {@code salonId} and is not the
     *                            {@code SALON_MASTER} of {@code masterId}
     */
    @Transactional
    public MasterServiceResponse updateMasterServiceBand(
            UUID actorId,
            UUID salonId,
            UUID masterId,
            UUID serviceDefId,
            UpdateMasterServiceBandRequest request) {

        // D5 defense-in-depth — re-prove the SpEL gate before loading anything (mirrors
        // deactivateServiceDefinition's enforceCanManageServiceDefinition idiom; actorId is
        // resolved by the controller via AuthenticationUtils.userId, same as that method).
        authz.enforceCanEditMasterServiceBand(actorId, salonId, masterId);

        MasterServiceAssignment assignment = masterServiceRepository
                .findByMasterIdAndServiceDefinitionId(masterId, serviceDefId)
                .filter(MasterServiceAssignment::isActive)
                .orElseThrow(() -> new NotFoundException(
                        "No active assignment for master " + masterId + " and service " + serviceDefId));

        Master master = assignment.getMaster();
        if (master.getSalon() == null || !master.getSalon().getId().equals(salonId)) {
            throw new ForbiddenException("Access denied");
        }
        ServiceDefinition serviceDef = assignment.getServiceDefinition();
        if (serviceDef.getOwnerType() != OwnerType.SALON || !serviceDef.getOwnerId().equals(salonId)) {
            throw new ForbiddenException("Service definition does not belong to this salon");
        }

        // D11 — capture the RESOLVED floor/duration before mutating, so the eviction/refresh
        // conditionals below compare actual values, not merely "was a field present".
        BigDecimal floorBefore = ServicePricing.effectivePriceOf(assignment);
        Integer durationBefore = assignment.getDurationOverrideMinutes();

        boolean bandFieldsPresent = request.priceType() != null
                || request.price() != null
                || request.priceMax() != null;
        if (Boolean.TRUE.equals(request.clearBand())) {
            // D2 — revert to Inherited: all three columns NULL, tracking the definition again.
            assignment.setPriceTypeOverride(null);
            assignment.setPriceOverride(null);
            assignment.setPriceMaxOverride(null);
        } else if (bandFieldsPresent) {
            // Already proven legal by the request's own bean validation (MasterServiceBand
            // .isLegal, via its @AssertTrue methods) — write the complete, own band atomically.
            assignment.setPriceTypeOverride(request.priceType());
            assignment.setPriceOverride(request.price());
            assignment.setPriceMaxOverride(request.priceMax());
        }
        // else: band untouched (D4 — null means "leave unchanged").

        if (Boolean.TRUE.equals(request.clearDurationOverride())) {
            assignment.setDurationOverrideMinutes(null);
        } else if (request.durationOverrideMinutes() != null) {
            assignment.setDurationOverrideMinutes(request.durationOverrideMinutes());
        }
        // else: duration untouched.

        BigDecimal floorAfter = ServicePricing.effectivePriceOf(assignment);
        Integer durationAfter = assignment.getDurationOverrideMinutes();

        // D11 — always: the band or duration may have changed what these caches serve.
        evictMasterServicesCache(List.of(masterId));
        evictSalonCatalogAfterCommit(salonId);
        // D11 — conditional: duration feeds slot maths, a band-only change does not.
        if (!Objects.equals(durationBefore, durationAfter)) {
            evictBookableFutureSlotsCache(List.of(masterId));
        }
        // D11 — conditional: min_effective_price is keyed off the floor alone; a RANGE ceiling
        // moving without the floor moving does not require a refresh.
        if (bigDecimalChanged(floorBefore, floorAfter)) {
            masterRepository.refreshMinEffectivePrice(masterId);
        }

        return MasterServiceResponse.from(assignment);
    }

    /** Null-safe, scale-insensitive BigDecimal inequality — {@code compareTo}, never {@code equals}. */
    private static boolean bigDecimalChanged(BigDecimal before, BigDecimal after) {
        if (before == null || after == null) {
            return !Objects.equals(before, after);
        }
        return before.compareTo(after) != 0;
    }

    /**
     * Unassigns ONE master from ONE service — the surgical, per-master counterpart of
     * {@link #deactivateServiceDefinition}, which removes a service from the WHOLE salon (every
     * master performing it, at once). Different row, different blast radius; neither replaces the
     * other (Phase 307).
     *
     * <p><b>Soft unassign only (D1).</b> {@code UPDATE master_services SET is_active = false},
     * never a row delete: {@code bookings.master_service_id} has no {@code ON DELETE} clause, so a
     * hard delete of a row carrying any booking — past or future — would abort with a foreign-key
     * violation instead of silently losing booking history. Every past booking keeps resolving its
     * service, provider and {@code price_at_booking} through the row exactly as before.
     *
     * <p><b>The shared {@link ServiceDefinition} is NEVER touched (D2).</b> Unassigning the last
     * master leaves it active and salon-owned with zero active assignments; it falls out of the
     * public catalogue on its own, by condition 3 of the Phase 305 D2 rule. Deactivating it here
     * would be a hidden salon-wide mutation triggered by a per-master action — rejected.
     *
     * <p><b>Refuses with {@code 409} when a future {@code CONFIRMED} booking still runs through
     * this exact assignment (D4) — nothing is written.</b> This is the shipping contract, not a
     * placeholder: Phase 308, which would replace the refusal with a cancel-and-notify cascade,
     * was deferred by the user on 2026-09-08. The count is scoped to THIS {@code master_services}
     * row, never to the master as a whole — a future booking for a DIFFERENT service the same
     * master performs must not block.
     *
     * <p>A second unassign of an already-inactive pair is a plain {@code 404} — idempotent by row
     * state, no write (D7).
     *
     * <p>{@code masterId} is the {@code masters} row primary key, NOT a {@code userId}, resolved
     * the same way {@link #assignServiceToMaster} resolves it. The manual
     * {@code masterBelongsToSalon} re-check below mirrors that method's own defense-in-depth
     * idiom: the controller's {@code @PreAuthorize} SpEL gate is never trusted alone.
     *
     * <p>The resolved assignment's {@link ServiceDefinition} is also re-checked against
     * {@code salonId} (same {@code ownerType}/{@code ownerId} check {@link #assignServiceToMaster}
     * already performs). The write-path invariant means a {@code master_services} row can only ever
     * link a master to a same-salon definition, so this branch is unreachable today — it exists so
     * this method is not the one place in the class relying on that invariant with no check of its
     * own.
     *
     * @throws NotFoundException  if the master does not exist, or no ACTIVE assignment exists for
     *                             this (master, serviceDef) pair
     * @throws ForbiddenException if the master does not belong to {@code salonId}
     * @throws BusinessException  (409) if a future CONFIRMED booking exists for this assignment
     */
    @Transactional
    public void unassignServiceFromMaster(UUID salonId, UUID masterId, UUID serviceDefId) {

        // MEDIUM-1/2 (Phase-307 perf audit) — one JOIN-FETCH query resolves the assignment AND its
        // master AND its service definition together, so the happy path costs ONE round trip
        // instead of three (a separate masterRepository.findById, this finder, and the lazy-proxy
        // init the ownership re-check below used to force on assignment.getServiceDefinition()).
        // The 404 discriminator below only queries again on the cold not-found branch — see
        // notFoundForUnassign — so the two distinct messages are preserved without the happy path
        // ever paying for them.
        MasterServiceAssignment assignment = masterServiceRepository
                .findByMasterIdAndServiceDefinitionId(masterId, serviceDefId)
                .filter(MasterServiceAssignment::isActive)
                .orElseThrow(() -> notFoundForUnassign(masterId, serviceDefId));

        Master master = assignment.getMaster();
        if (master.getSalon() == null || !master.getSalon().getId().equals(salonId)) {
            throw new ForbiddenException("Access denied");
        }

        ServiceDefinition serviceDef = assignment.getServiceDefinition();
        if (serviceDef.getOwnerType() != OwnerType.SALON || !serviceDef.getOwnerId().equals(salonId)) {
            throw new ForbiddenException("Service definition does not belong to this salon");
        }

        // D4 — refuse outright rather than write anything when a future CONFIRMED booking still
        // runs through THIS assignment. Scoped to assignment.getId() (master_service_id), never to
        // masterId alone, so a future booking for a different service this master performs does
        // not block (case 7).
        long futureConfirmedCount = bookingRepository.countConfirmedFutureByMasterServiceId(
                masterId, assignment.getId(), OffsetDateTime.now(clock));
        if (futureConfirmedCount > 0) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "Master has " + futureConfirmedCount + " future confirmed booking(s) for this "
                            + "service; cancel or decline them first");
        }

        // Register every eviction BEFORE the write (mirrors deactivateServiceDefinition): each
        // evictXAfterCommit call only REGISTERS an afterCommit synchronization, so a throw earlier
        // in this method leaves them un-registered and a throw after this point still runs them
        // only once the transaction actually commits (anti-bug §F rule 2).
        //
        // MEDIUM-3 (Phase-307 perf audit) — no direct evictAvailableSlotsCache call here. It scans
        // the SAME "available-slots" cache, for the SAME masterId, SYNCHRONOUSLY on the committing
        // thread — exactly what MasterCachePrefixEvictor's own javadoc says must not sit on the
        // critical path. evictBookableFutureSlotsCache's off-thread sweep
        // (SlotCalculationService#evictMasterAvailabilityCaches, BOOKING_WRITE_CACHES) already
        // covers "available-slots" as a superset, so the synchronous call was pure duplicate work.
        evictMasterServicesCache(List.of(masterId));
        evictBookableFutureSlotsCache(List.of(masterId));
        evictSalonCatalogAfterCommit(salonId);

        // D1 — soft unassign: flip is_active, never delete the row.
        assignment.setActive(false);

        masterRepository.refreshMinEffectivePrice(masterId);
    }

    /**
     * Cold-path 404 discriminator for {@link #unassignServiceFromMaster} — only reached when the
     * combined JOIN-FETCH finder above found no active assignment for {@code (masterId,
     * serviceDefId)}. One extra {@code existsById} distinguishes "the master row itself does not
     * exist" from "the master exists but has no active assignment for this service", so the happy
     * path never pays for the two distinct 404 messages.
     */
    private NotFoundException notFoundForUnassign(UUID masterId, UUID serviceDefId) {
        if (!masterRepository.existsById(masterId)) {
            return new NotFoundException("Master not found: " + masterId);
        }
        return new NotFoundException(
                "No active assignment for master " + masterId + " and service " + serviceDefId);
    }

    @Transactional
    public MasterServiceResponse addIndependentMasterService(
            UUID userId,
            CreateServiceDefinitionRequest request) {

        Master master = masterRepository.findByUserId(userId)
                .orElseThrow(() -> new NotFoundException("Master not found for user: " + userId));

        if (master.getMasterType() != MasterType.INDEPENDENT_MASTER) {
            throw new ForbiddenException("Only independent masters can add their own services");
        }

        validateCategoryActive(request.category());

        ServiceType serviceType = resolveRequiredServiceType(request.serviceTypeId(), request.category());

        // Same accepted trade-off as addServiceToSalon: one SELECT on the success path buys the
        // client a deep-linkable existingServiceDefId on the rare conflict. See the method javadoc.
        assertNoActiveDuplicate(OwnerType.INDEPENDENT_MASTER, master.getId(), serviceType, null);

        ServiceDefinition definition = ServiceDefinition.builder()
                .ownerType(OwnerType.INDEPENDENT_MASTER)
                .ownerId(master.getId())
                .name(resolveCreateName(request.name(), serviceType))
                .description(request.description())
                .category(request.category())
                .baseDurationMinutes(request.baseDurationMinutes())
                .bufferMinutesAfter(request.bufferMinutesAfter())
                .isActive(true)
                .build();

        applyPriceMode(definition, request.priceType(), request.price(), request.priceMin(), request.priceMax());
        definition.setServiceType(serviceType);

        // PERF: plain save, then ONE flush after the assignment save below — deliberately NOT
        // persistDefinition's saveAndFlush. This path emits two INSERTs (definition, then the
        // assignment that FKs to it), and be precise about what deferring actually saves:
        // Hibernate batches PER TABLE, so `service_definitions` and `master_services` are two
        // separate PreparedStatement batches — two executeBatch round-trips EITHER WAY. What the
        // single flush eliminates is one FLUSH CYCLE (a dirty-check + auto-flush pass over the
        // whole persistence context), not a round trip. Neutral-to-marginally-positive, and it
        // costs nothing: nothing between the two statements reads the DB, the id is available
        // without a flush (GenerationType.UUID is assigned in-memory at persist time) so the
        // assignment can reference savedDef immediately, and order_inserts=true emits the
        // definition before the assignment. Same reasoning as bulkCreateForMaster's save +
        // flushBulkBatch — where the win IS round trips, because that path queues N rows of the
        // SAME table into one batch. addServiceToSalon keeps persistDefinition because it has no
        // second statement to defer for.
        ServiceDefinition savedDef = serviceRepository.save(definition);

        MasterServiceAssignment assignment = MasterServiceAssignment.builder()
                .master(master)
                .serviceDefinition(savedDef)
                .isActive(true)
                .build();

        MasterServiceAssignment savedAssignment = masterServiceRepository.save(assignment);

        // The DUPLICATE_SERVICE classification is preserved because it is the FLUSH that must sit
        // inside the try/catch, never the save: save on a new entity only assigns the id in
        // memory, so the unique-index violation can only surface when the INSERT actually reaches
        // the DB. Flushing here rather than at commit is what keeps that violation catchable —
        // at commit it would land outside any catch and degrade to the generic 409.
        flushTranslatingDuplicateViolation(definition.getName());

        // PERF-M2: keep the pre-computed min_effective_price in sync for the
        // independent master's own search entry.
        masterRepository.refreshMinEffectivePrice(master.getId());

        // Evict only this master's cache entry after commit — replacing allEntries=true
        // to avoid cold-miss DB round-trips for all other masters (anti-bug §F).
        evictMasterServicesCache(List.of(master.getId()));

        return MasterServiceResponse.from(savedAssignment);
    }

    /**
     * Additive bulk service creation for an INDEPENDENT_MASTER acting on their own behalf.
     *
     * <p>The acting master is resolved from the authenticated principal's {@code userId}
     * (never a client-supplied id), mirroring {@link #addIndependentMasterService}. The
     * batch is created all-or-nothing in this single transaction.
     *
     * <p>Usable whether or not the master already has services — it backs both the initial
     * catalogue-setup screen and later "add more services" passes.
     *
     * @throws ForbiddenException        if the user is not an INDEPENDENT_MASTER
     * @throws DuplicateServiceException (409) if a batch item's service type is already offered
     */
    @Transactional
    public List<MasterServiceResponse> bulkCreateIndependentMasterServices(
            UUID userId,
            BulkCreateServicesRequest request) {

        Master master = masterRepository.findByUserId(userId)
                .orElseThrow(() -> new NotFoundException("Master not found for user: " + userId));

        if (master.getMasterType() != MasterType.INDEPENDENT_MASTER) {
            throw new ForbiddenException("Only independent masters can add their own services");
        }

        return bulkCreateForMaster(master, OwnerType.INDEPENDENT_MASTER, master.getId(), request);
    }

    /**
     * Additive bulk service creation performed by a SALON_OWNER/SALON_ADMIN on behalf of a
     * master in their salon (including the owner-operated master row).
     *
     * <p>Salon-membership of the target master is verified here as the second half of the
     * controller's {@code @PreAuthorize} role gate (anti-bug §D split), mirroring
     * {@link #assignServiceToMaster}.
     *
     * <p><b>Phase 302 D1 — the definitions are SALON-owned.</b> A salon-bound master's services
     * persist as {@code ownerType = SALON, ownerId = salonId}, not as master-owned rows. That is
     * the ownership the salon catalogue query
     * ({@code MasterServiceRepository#findBookableAssignmentsBySalon}) requires, so a service
     * created here is actually visible in {@code GET /salons/&#123;salonId&#125;/services};
     * master-owned rows never were. The per-master {@link MasterServiceAssignment} remains the
     * thing that says <em>this master performs this service</em>.
     *
     * <p><b>{@code masterId} is the {@code masters} row primary key, NOT a {@code userId}</b> — it
     * is resolved by {@code masterRepository.findById} below. Passing a user id yields
     * {@code 404 Master not found}, not {@code 403}.
     *
     * <p>Usable whether or not the master already has services — it backs both the initial
     * catalogue-setup screen and later "add more services" passes.
     *
     * @throws ForbiddenException        if the master does not belong to the given salon
     * @throws DuplicateServiceException (409) if <em>this master</em> already offers a batch
     *                                   item's service type (Phase 302 D4 — the salon already
     *                                   offering it is the reuse path, not a conflict)
     */
    @Transactional
    public List<MasterServiceResponse> bulkCreateSalonMasterServices(
            UUID salonId,
            UUID masterId,
            BulkCreateServicesRequest request) {

        Master master = masterRepository.findById(masterId)
                .orElseThrow(() -> new NotFoundException("Master not found: " + masterId));

        if (master.getSalon() == null || !master.getSalon().getId().equals(salonId)) {
            throw new ForbiddenException("Access denied");
        }

        return bulkCreateForMaster(master, OwnerType.SALON, salonId, request);
    }

    /**
     * Shared additive bulk-create core for a resolved master.
     *
     * <p><b>Ownership is per entry point, not universal (Phase 302 D1).</b> The two callers pass
     * different owners and that difference is load-bearing:
     * <ul>
     *   <li>{@link #bulkCreateIndependentMasterServices} — {@code (INDEPENDENT_MASTER, master.id)}.
     *       Its master rows have {@code salon_id IS NULL}; there is no salon to own anything.</li>
     *   <li>{@link #bulkCreateSalonMasterServices} — {@code (SALON, salonId)}. The salon catalogue
     *       query admits only SALON-owned definitions, so anything else is created invisible.</li>
     * </ul>
     * An earlier revision of this javadoc claimed a single universal "owned by the master row"
     * rule; that claim was false against the catalogue query and is retired.
     *
     * <p><b>Find-or-create on the SALON branch (D2).</b> A salon owner is shared by every master
     * in the salon, and V121's partial unique index
     * {@code ux_service_def_owner_service_type_active} makes one ACTIVE definition per
     * {@code (owner_type, owner_id, service_type_id)} a hard database rule. Two masters in one
     * salon who both perform «Манікюр» therefore cannot each mint a definition — the second insert
     * would be refused. So the SALON branch REUSES the salon's existing active definition for a
     * service type and only creates one when none exists, then inserts this master's assignment
     * either way. The reuse lookup and the per-master conflict check are ONE salon-scoped query
     * ({@link ServiceRepository#findSalonBulkSetupCandidates}) — the same collision projection
     * shape the owner-level guard uses, with its outcome split into <em>reuse</em> (the salon's
     * own definition) and <em>throw</em> (this master already performs it); no parallel lookup is
     * written (REUSE-FIRST).
     *
     * <p><b>Reuse never mutates the shared definition (D3).</b> Name, base price, duration and
     * category on a reused definition are salon-level facts shared by every master performing it;
     * rewriting them from one master's batch item would silently change what the whole salon
     * offers. Per-master divergence goes where it already belongs — the {@code master_services}
     * band ({@code price_type_override} / {@code price_override} / {@code price_max_override},
     * Phase 311) and {@code duration_override_minutes} — and is left {@code NULL} (Inherited)
     * when the item's full band matches the definition's.
     *
     * <p><b>Shape divergence is STORED, not rejected (Phase 312 — supersedes the re-audit
     * MEDIUM-1 guard this javadoc used to describe).</b> Before V165, {@code master_services} had
     * only a floor and a duration — no per-master price TYPE and no per-master ceiling — so a
     * batch item whose price type or RANGE ceiling disagreed with the reused definition had
     * nowhere faithful to land and was rejected with {@code 400 SERVICE_PRICE_SHAPE_MISMATCH}.
     * Phase 311's {@code price_type_override}/{@code price_max_override} columns make every
     * shape representable, so Phase 312 retires that guard: see
     * {@link #resolveBulkReuseBand}, which now stores the item's own band instead.
     *
     * <p><b>The conflict is per-MASTER on the SALON branch (D4).</b> "The salon already offers
     * this type" is the reuse path, not a conflict; {@code 409 DUPLICATE_SERVICE} fires only when
     * <em>this master</em> already has an active assignment for the type. The INDEPENDENT_MASTER
     * branch keeps the owner-level definition guard verbatim — for it, owner and master are the
     * same row, and the guard additionally catches an active definition carrying no assignment,
     * which the assignment-level check cannot see.
     *
     * <p>Rejects duplicate {@code serviceTypeId}s within the batch, derives each service name +
     * category from the chosen {@link ServiceType}, reuses {@link #applyPriceMode} for the
     * validated price mode, and persists the whole batch transactionally (all-or-nothing).
     */
    private List<MasterServiceResponse> bulkCreateForMaster(
            Master master,
            OwnerType ownerType,
            UUID ownerId,
            BulkCreateServicesRequest request) {

        rejectDuplicateServiceTypeIds(request.items());

        // PERF: resolve all service types in ONE query (the ids are already distinct,
        // guaranteed by rejectDuplicateServiceTypeIds) instead of N serialized findById
        // calls, then validate every DISTINCT derived category in ONE query instead of a
        // SELECT EXISTS per item. Both walks share this resolved-types map.
        //
        // These three steps run OUTSIDE the advisory lock (backend-perf finding 2): they are
        // pure in-memory validation plus reads of GLOBAL reference data (service_types,
        // platform_categories) — no owner-scoped state, so serializing them buys nothing while
        // extending the lock hold by ~2 DB round-trips on every call. Hoisting them also means a
        // request that 400s/404s on a bad service type never takes the lock at all.
        Map<UUID, ServiceType> typesById = resolveBulkServiceTypes(request.items());
        validateBulkCategoriesActive(typesById.values());

        // TOCTOU guard: serialize concurrent additive bulk adds against the same DEFINITION key
        // space. The guard below is a read-then-write check — it reads which of the batch's
        // service types are already taken, then inserts. Two concurrent bulk POSTs could
        // therefore both read "type X is free" and both proceed. The V121 unique index is the
        // correctness backstop (the loser 500s at flush and is translated to a 409), but taking a
        // transaction-scoped advisory lock makes the second caller wait for the first to commit,
        // so its guard sees the committed rows and either REUSES them or produces the clean,
        // item-naming 409 instead of a raced constraint violation. Mirrors the booking
        // overlap-guard advisory lock (anti-bug pattern).
        //
        // THE KEY IS THE OWNER, NOT THE MASTER (phase-302 audit HIGH-2). V121 keys on
        // (owner_type, owner_id, service_type_id), so on the SALON branch the contended resource
        // is the SALON's definition set, shared by every master in it. Keying on masterId let two
        // DIFFERENT masters of one salon take two DIFFERENT locks, both miss
        // findSalonBulkSetupCandidates' reuse arm, and both INSERT (SALON, salonId, typeId) — the
        // loser then tripping V121 at flush, where flushBulkBatch can only translate a constraint
        // NAME, so the client got a DUPLICATE_SERVICE 409 with serviceName AND existingServiceDefId
        // both null. Locking the owner is strictly stronger than locking the master (two batches
        // for one master are also two batches for its salon), and exactly ONE key is taken per
        // transaction, so no lock-ordering discipline is needed.
        //
        // The serialized window is deliberately NARROW — it opens here and closes at commit,
        // covering exactly the read-then-write span: the duplicate guard, the inserts, the flush,
        // and the min_effective_price refresh that must not be observed out of order. Everything
        // above needs no serialization.
        //
        // The cache eviction below is NOT inside this window and must not be: evictMasterServicesCache
        // only REGISTERS an afterCommit synchronization, so the actual evict runs after the
        // transaction commits and therefore after this lock is released (anti-bug §F rule 2 — an
        // inline evict would let a concurrent reader repopulate the cache from a pre-commit snapshot).
        acquireBulkSetupLockWithTimeout(ownerId);

        // PERF: and the duplicate guard for the WHOLE batch in ONE query, rather than one
        // per item. Which guard applies depends on who owns the definitions (D4):
        //
        //   SALON  — the owner is shared by every master in the salon, so an owner-level
        //            definition guard would reject the salon's SECOND master from ever offering
        //            a type the first already offers. The conflict is per-MASTER, the salon-level
        //            hit is the reuse path, and resolveSalonBulkCandidates answers BOTH from one
        //            query (audit LOW-3 — three round-trips inside a now salon-wide serialized
        //            window multiplied across every concurrent master setup in the salon).
        //   INDEPENDENT_MASTER — owner and master are the same row, so the pre-existing
        //            owner-level definition guard is kept verbatim. It is strictly stronger here:
        //            it also catches an active definition carrying no active assignment, which
        //            would still violate V121 at INSERT but is invisible to an assignment check.
        //
        // Both guards run INSIDE the advisory lock, exactly as before: each is a read-then-write
        // check whose only race-proof backstop is the V121 index / the master_services unique key.
        Map<UUID, ServiceDefinition> reusableByTypeId;
        // Phase 307 D6 — type id -> the master's own INACTIVE master_services row for that
        // reused definition, if any. When present, createSingleFromBulkItem must REACTIVATE this
        // exact row instead of inserting a second one (master_services' UNIQUE (master_id,
        // service_def_id) is NOT partial). Empty on the INDEPENDENT_MASTER branch, which never
        // reuses a definition.
        Map<UUID, UUID> reactivateAssignmentIdByTypeId;
        if (ownerType == OwnerType.SALON) {
            SalonBulkCandidateResolution resolution = resolveSalonBulkCandidates(
                    master.getId(), ownerId, request.items(), typesById);
            reusableByTypeId = resolution.reusableByTypeId();
            reactivateAssignmentIdByTypeId = resolution.reactivateAssignmentIdByTypeId();
        } else {
            assertNoActiveDuplicatesInBatch(ownerType, ownerId, request.items(), typesById);
            reusableByTypeId = Map.of();
            reactivateAssignmentIdByTypeId = Map.of();
        }

        List<MasterServiceResponse> created = request.items().stream()
                .map(item -> createSingleFromBulkItem(
                        master, ownerType, ownerId, item, typesById.get(item.serviceTypeId()),
                        reusableByTypeId.get(item.serviceTypeId()),
                        reactivateAssignmentIdByTypeId.get(item.serviceTypeId())))
                .toList();

        // Push the whole batch to the DB in one go, translating a V121 violation exactly as the
        // single-item persistDefinition does. Must happen BEFORE refreshMinEffectivePrice below:
        // that is a JPQL @Modifying query, so Hibernate's AUTO flush would emit the inserts from
        // inside it — outside any catch — and the race would degrade to a generic 409.
        flushBulkBatch();

        // Keep the pre-computed min_effective_price in sync for the master's search entry
        // (PERF-M2) and evict the master's services cache after commit (anti-bug §F).
        masterRepository.refreshMinEffectivePrice(master.getId());
        evictMasterServicesCache(List.of(master.getId()));
        // Phase 304 D1: a SALON-branch batch persists SALON-owned definitions/assignments, which
        // can change what GET /salons/{salonId}/services returns — evict that salon's catalogue
        // after commit (REUSE-FIRST: the existing helper, called from one more place). The
        // INDEPENDENT_MASTER branch's ownerId is the master's own row, not a salon id, so it stays
        // a no-op here rather than mis-evicting a "salon" keyed by a master id.
        if (ownerType == OwnerType.SALON) {
            evictSalonCatalogAfterCommit(ownerId);
        }

        return created;
    }

    /**
     * Takes the transaction-scoped advisory lock that serializes the additive bulk-create critical
     * section, bounding the wait at the 3s {@code lock_timeout} the repository query fuses into
     * the same round-trip. The lock lives in its own salt-{@code 2} key space, so it contends only
     * with other bulk setups for the same key — never with the booking master/client locks (salts
     * {@code 0}/{@code 1}); see
     * {@link MasterServiceRepository#acquireBulkSetupLockWithTimeout(UUID)}.
     *
     * <p><b>{@code ownerId} — the V121 key space, not the master (audit HIGH-2).</b> The contended
     * resource is one ACTIVE definition per {@code (owner_type, owner_id, service_type_id)}: the
     * SALON on the salon branch, the master row on the independent branch (where owner and master
     * are the same row). Keying on the master in a salon let two masters of one salon insert the
     * same {@code (SALON, salonId, typeId)} and the loser trip V121 at flush, producing a 409 with
     * a null {@code serviceName} AND a null {@code existingServiceDefId}.
     *
     * <p><b>Why a bounded wait rather than {@code pg_try_advisory_xact_lock}.</b> Under a
     * try-lock the loser of ORDINARY contention fails instantly, before it can re-check
     * duplicates against the winner's committed rows — so it would return a bare "already in
     * progress" instead of the 409 {@code DUPLICATE_SERVICE} carrying a populated
     * {@code existingServiceDefId} that the mobile screen deep-links on. A bounded wait keeps
     * normal contention behaving exactly as before (wait → acquire → re-check → clean 409) and
     * aborts only pathological contention, which is the pool-saturation case worth bounding.
     *
     * @throws BusinessException 503 when the wait exceeds {@code lock_timeout} (Postgres
     *                           {@code 55P03 lock_not_available}, surfaced by Spring/Hibernate
     *                           exception translation as
     *                           {@link PessimisticLockingFailureException}) — a transient
     *                           "setup is busy, retry" condition, deliberately distinct from the
     *                           semantically loaded 409 this endpoint reserves for
     *                           {@code DUPLICATE_SERVICE}; 500 when the lock query returns no row
     */
    private void acquireBulkSetupLockWithTimeout(UUID ownerId) {
        Integer lockResult;
        try {
            lockResult = masterServiceRepository.acquireBulkSetupLockWithTimeout(ownerId);
        } catch (PessimisticLockingFailureException ex) {
            // Never echo SQL state, the timeout value or the driver cause to the caller (§I/§N);
            // only the exception's simple class name, at DEBUG, for server-side triage.
            log.debug("Bulk-setup lock wait exceeded lock_timeout: {}", ex.getClass().getSimpleName());
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Service setup is busy for this master, please retry");
        }
        if (lockResult == null) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Bulk-setup lock acquisition failed");
        }
    }

    /**
     * Batch-resolves every {@link ServiceType} referenced by the bulk items in ONE
     * {@code findAllById} query (the ids are already distinct — see
     * {@link #rejectDuplicateServiceTypeIds}). Each requested id must exist and be
     * active, otherwise the same errors the per-item path raised are thrown: a missing
     * id is a 404 {@link NotFoundException}, an inactive type is a 400.
     *
     * @return a map keyed by service-type id, covering every requested id
     */
    private Map<UUID, ServiceType> resolveBulkServiceTypes(List<BulkServiceItemRequest> items) {
        List<UUID> ids = items.stream()
                .map(BulkServiceItemRequest::serviceTypeId)
                .toList();

        Map<UUID, ServiceType> typesById = serviceTypeRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(ServiceType::getId, Function.identity()));

        for (UUID id : ids) {
            ServiceType type = typesById.get(id);
            if (type == null) {
                throw new NotFoundException("ServiceType not found: " + id);
            }
            if (!type.isActive()) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "Service type is not active");
            }
        }
        return typesById;
    }

    /**
     * Validates every DISTINCT category derived from the resolved service types in a
     * SINGLE query (PERF: collapses up to N {@code SELECT EXISTS} into one
     * {@code ... WHERE name IN (:names)}). The derived categories are highly duplicated
     * across items, so the distinct set is typically tiny. Any requested category not
     * returned as APPROVED + active triggers the same 400 as
     * {@link #validateCategoryActive(String)}.
     */
    private void validateBulkCategoriesActive(Collection<ServiceType> types) {
        Set<String> requested = types.stream()
                .map(ServiceType::getPlatformCategoryName)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (requested.isEmpty()) {
            return;
        }

        Set<String> selectable = Set.copyOf(platformCategoryRepository.findSelectableNamesIn(requested));
        for (String category : requested) {
            if (!selectable.contains(category)) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "Unknown category: " + category);
            }
        }
    }

    /**
     * Creates one {@link ServiceDefinition} + {@link MasterServiceAssignment} from a bulk
     * item, using the pre-resolved {@link ServiceType} (type existence, active-check,
     * category validation, and the V121 duplicate guard are all already done in batch by the
     * caller). The service-type's name + parent category are the source of truth for the
     * persisted name and category (no free-text name accepted).
     *
     * <p><b>Deliberately does no I/O beyond the two {@code save} calls.</b> Every check that
     * would need a query lives in {@link #bulkCreateForMaster} so the per-item cost stays
     * O(0) queries — see {@link #assertNoActiveDuplicatesInBatch}. Nothing is flushed here;
     * the caller flushes the batch once.
     *
     * <p><b>Reuse branch (Phase 302 D2/D3, band resolution Phase 312 D3/D9).</b> When
     * {@code reusable} is non-null the salon already has an ACTIVE definition for this service
     * type, so ONLY the assignment is inserted — creating a second definition would violate
     * V121's {@code ux_service_def_owner_service_type_active}. The reused definition is left
     * byte-identical; {@link #resolveBulkReuseBand} decides whether the item's band matches the
     * reused definition's (Inherited — all three {@code master_services} band columns
     * {@code NULL}) or must be stored as the master's own, fully-specified band. Duration follows
     * its own, independent "override when it differs" rule via {@link #overrideDurationFor}
     * (Phase 311 D2 — duration carries no shape and no ceiling).
     *
     * <p>Since V165 (Phase 311) every shape the item can express is representable on the
     * assignment — the superseded Phase 302 guard that used to reject a differing shape
     * ({@code assertReusableShapesAreRepresentable} / {@code isShapeRepresentableOnAssignment})
     * is retired (Phase 312 D3); this branch now STORES a differing shape instead of 400ing it.
     *
     * <p><b>Reactivation sub-branch (Phase 307 D6).</b> When {@code reactivateAssignmentId} is
     * ALSO non-null, the master holds an {@code is_active = false} row for the reused definition —
     * a service unassigned earlier via {@code ServiceCatalogService#unassignServiceFromMaster}.
     * {@code master_services}' {@code UNIQUE (master_id, service_def_id)} is NOT partial, so an
     * INSERT here would trip it at flush; the existing row is reactivated and its overrides
     * refreshed from this item instead.
     *
     * @param reusable               the salon's existing active definition for this service type,
     *                               or {@code null} to create one (always {@code null} on the
     *                               INDEPENDENT_MASTER branch)
     * @param reactivateAssignmentId the master's own INACTIVE {@code master_services} row id for
     *                               {@code reusable}, or {@code null} to insert a fresh assignment
     *                               (always {@code null} when {@code reusable} is {@code null})
     */
    private MasterServiceResponse createSingleFromBulkItem(
            Master master,
            OwnerType ownerType,
            UUID ownerId,
            BulkServiceItemRequest item,
            ServiceType serviceType,
            @Nullable ServiceDefinition reusable,
            @Nullable UUID reactivateAssignmentId) {

        if (reusable != null) {
            if (reactivateAssignmentId != null) {
                MasterServiceAssignment existing = masterServiceRepository.findById(reactivateAssignmentId)
                        .orElseThrow(() -> new IllegalStateException(
                                "Reactivation candidate vanished mid-transaction: " + reactivateAssignmentId));
                // Avoid a lazy load of existing.serviceDefinition: reusable is the same row,
                // already hydrated by findSalonBulkSetupCandidates in this transaction.
                existing.setServiceDefinition(reusable);
                existing.setActive(true);
                ResolvedBand band = resolveBulkReuseBand(item, reusable);
                existing.setPriceTypeOverride(band.priceType());
                existing.setPriceOverride(band.price());
                existing.setPriceMaxOverride(band.priceMax());
                existing.setDurationOverrideMinutes(overrideDurationFor(item, reusable));
                return MasterServiceResponse.from(existing);
            }

            ResolvedBand band = resolveBulkReuseBand(item, reusable);
            MasterServiceAssignment reuseAssignment = MasterServiceAssignment.builder()
                    .master(master)
                    .serviceDefinition(reusable)
                    .priceTypeOverride(band.priceType())
                    .priceOverride(band.price())
                    .priceMaxOverride(band.priceMax())
                    .durationOverrideMinutes(overrideDurationFor(item, reusable))
                    .isActive(true)
                    .build();

            return MasterServiceResponse.from(masterServiceRepository.save(reuseAssignment));
        }

        String category = serviceType.getPlatformCategoryName();

        ServiceDefinition definition = ServiceDefinition.builder()
                .ownerType(ownerType)
                .ownerId(ownerId)
                .name(serviceType.getNameUk())
                .category(category)
                .baseDurationMinutes(item.durationMinutes())
                .bufferMinutesAfter(0)
                .isActive(true)
                .build();

        applyPriceMode(definition, item.priceType(), item.price(), item.priceMin(), item.priceMax());
        definition.setServiceType(serviceType);

        // PERF: plain save, NOT persistDefinition's saveAndFlush. Flushing per item turns the
        // batch's ~4 batched round-trips (hibernate.jdbc.batch_size=50 + order_inserts=true)
        // into ~2N. The caller flushes the whole batch once via flushBulkBatch, inside the same
        // DataIntegrityViolationException translation, so the DUPLICATE_SERVICE 409 is preserved.
        // The id is available without a flush — GenerationType.UUID is assigned in-memory at
        // persist time — so the assignment below can reference savedDef immediately, and
        // order_inserts emits every definition before any assignment that FKs to it.
        ServiceDefinition savedDef = serviceRepository.save(definition);

        MasterServiceAssignment assignment = MasterServiceAssignment.builder()
                .master(master)
                .serviceDefinition(savedDef)
                .isActive(true)
                .build();

        return MasterServiceResponse.from(masterServiceRepository.save(assignment));
    }

    /**
     * Rejects a batch that toggles the same service type on twice. Without this guard a
     * caller could create two near-identical services in one call, which the multi-select
     * setup screen never intends.
     *
     * <p><b>Deliberately a 400, NOT the {@code DUPLICATE_SERVICE} 409</b> (decision made
     * explicit alongside V121, previously accidental). The two failures are different kinds:
     * <ul>
     *   <li>409 {@code DUPLICATE_SERVICE} = the request conflicts with <em>server state</em> —
     *       the owner already has this service. The client's remedy is to look at the existing
     *       row (hence the {@code existingServiceDefId} in the payload).</li>
     *   <li>400 here = the <em>payload itself</em> is self-inconsistent; nothing exists yet and
     *       no server state is involved. The client's remedy is to deduplicate its own rows
     *       before resubmitting. Returning a 409 with a null {@code existingServiceDefId} would
     *       give the mobile screen a code it cannot act on and would blur the two branches.</li>
     * </ul>
     *
     * <p>Known gap (not introduced here, out of scope for V121): {@code handleBusiness}
     * genericises every {@code BAD_REQUEST} body to {@code "Invalid request"} with no
     * {@code errors} map, so this 400 currently carries no {@code items[i].serviceTypeId} field
     * path for the setup screen to resolve back to a row. The structured-field-error channel is
     * driven by Bean Validation ({@code MethodArgumentNotValidException}); wiring a service-layer
     * throw into it needs a new exception type + handler and belongs in its own change.
     */
    private void rejectDuplicateServiceTypeIds(List<BulkServiceItemRequest> items) {
        long distinct = items.stream()
                .map(BulkServiceItemRequest::serviceTypeId)
                .distinct()
                .count();
        if (distinct != items.size()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "Duplicate service type in bulk request");
        }
    }

    @Transactional(readOnly = true)
    // sync = true collapses the thundering herd: when a popular master's entry expires
    // (10-min TTL) only ONE thread runs the JOIN-FETCH graph query while concurrent callers
    // wait for it, instead of N identical queries firing on this public read (Anti-Bug §F-7).
    @Cacheable(value = "masterServices", key = "#masterId", sync = true)
    public List<MasterServiceResponse> getMasterServices(UUID masterId) {
        // An unknown masterId produces an empty list — the existsById check was a
        // redundant DB round-trip because the JOIN FETCH graph query already returns
        // nothing for a non-existent master.
        //
        // fromPublic masks priceOverride: this method backs ONLY the permitAll browse route
        // (ServiceController#getMasterServices), so an anonymous caller must not learn whether a
        // master prices away from their salon's definition. Masking happens INSIDE the cached
        // method on purpose — the "masterServices" cache is populated by, and read by, this public
        // path alone, so the cache holds the already-masked shape and no authenticated path can
        // pick up a masked entry. The provider's own view goes through getMyServices, which is
        // uncached and keeps the full variant.
        return masterServiceRepository
                .findByMasterIdAndIsActiveTrueWithGraph(masterId, PageRequest.of(0, 200))
                .stream()
                .map(MasterServiceResponse::from)
                .map(MasterServiceResponse::fromPublic)
                .toList();
    }

    /**
     * Returns the authenticated master's OWN active services.
     *
     * <p><strong>Owner-scoping:</strong> the master is resolved from the principal's
     * {@code userId} (the same {@link MasterRepository#findByUserId} resolution the create
     * path uses) — never from a client-supplied id. A caller can therefore only read their
     * own services, never another master's.
     *
     * <p>Returns the same active, owner-scoped list as {@link #getMasterServices(UUID)}
     * (the public browse), just resolved via the authenticated principal instead of a path
     * parameter. The result is intentionally NOT cached: owner reads are low-volume and
     * authenticated, so a dedicated cache surface would add eviction wiring on every
     * service mutation path for little benefit.
     */
    @Transactional(readOnly = true)
    public List<MasterServiceResponse> getMyServices(UUID userId) {
        Master master = masterRepository.findByUserId(userId)
                .orElseThrow(() -> new NotFoundException("Master not found for user: " + userId));

        return masterServiceRepository
                .findByMasterIdAndIsActiveTrueWithGraph(master.getId(), PageRequest.of(0, 200))
                .stream()
                .map(MasterServiceResponse::from)
                .toList();
    }

    /**
     * Returns the FULL service list of one master in {@code salonId}, {@code priceOverride}
     * unmasked — the salon OWNER/ADMIN management read this class did not previously expose
     * (Phase 309 D1). Same query and shape as {@link #getMyServices}, just resolved from a
     * caller-supplied {@code (salonId, masterId)} pair behind an ownership gate instead of the
     * principal's own {@code userId}.
     *
     * <p><b>Defense-in-depth (Phase 309 D2), same shape as {@link #unassignServiceFromMaster}'s
     * precedent, DIFFERENT failure code on the master-ownership half.</b> The controller's
     * {@code @PreAuthorize} already checks {@code @authz.canReadSalonMasterServices}; {@code
     * actorId} re-proves the identical salon-management predicate here via {@link
     * com.beautica.common.security.AuthorizationService#hasManagementAccess(UUID, UUID)} so a
     * future non-HTTP caller cannot bypass the SpEL gate — mirrors {@code
     * deactivateServiceDefinition}'s idiom. The master-belongs-to-salon half intentionally does
     * NOT go through {@code @authz.masterBelongsToSalon} — that predicate is wired into {@code
     * unassignServiceFromMaster}'s {@code @PreAuthorize} SpEL, where a cross-salon {@code
     * masterId} 403s. Phase 309 D2 requires the OPPOSITE here: a cross-salon {@code masterId}
     * must 404, indistinguishable from "no such master". So this method calls {@link
     * MasterRepository#existsByIdAndSalonId} directly — the exact predicate {@code
     * masterBelongsToSalon} itself delegates to — and denies with {@link NotFoundException}
     * instead of {@link ForbiddenException}.
     *
     * <p><b>Own-row branch (Phase 310 D2/D2.4).</b> A {@code SALON_MASTER} reading their own row
     * always fails {@code hasManagementAccess} — they are neither {@code SALON_OWNER} nor {@code
     * SALON_ADMIN} — so it alone would wrongly 403 the exact caller Phase 310 widened the SpEL
     * gate to admit. Before refusing, this method re-derives the identical own-row grant the SpEL
     * predicate already proved: the resolved {@code masters} row's {@code user_id} must be the
     * actor, AND {@code salonId} must actually own that row ({@code masterBelongsToSalon}, D2.4)
     * — never compare against the {@code masters} row id itself, only {@code masters.user_id}
     * (user id vs. row id is the confusion this defense-in-depth check exists to catch). The
     * {@code SALON_OWNER}/{@code SALON_ADMIN} fast path above is unchanged — this branch only
     * runs when {@code hasManagementAccess} has already failed, so it costs no extra query on the
     * owner/admin path.
     *
     * <p><b>D3 query reuse (perf MEDIUM, 2026-09-11).</b> The own-row branch's {@code
     * masterBelongsToSalon} call and the D3 check below both resolve {@code
     * existsByIdAndSalonId(masterId, salonId)} — same predicate, same arguments. On the accepted
     * own-row path this method captures that result and reuses it for D3 instead of issuing the
     * identical query twice. The owner/admin fast path is untouched: {@code masterBelongsToSalon}
     * is never computed there, so D3 still issues its own query, preserving that path's existing
     * query count.
     *
     * <p>{@code masterId} is the {@code masters} row primary key, NOT a {@code userId} (D3) — a
     * user id never satisfies {@code existsByIdAndSalonId} and so also 404s.
     *
     * <p><b>NOT cached (D4).</b> Mirrors {@link #getMyServices}: a low-volume, authenticated
     * management read. The public {@code masterServices} cache — populated ONLY by {@link
     * #getMasterServices(UUID)} with the masked shape — is never read, populated or evicted
     * here.
     *
     * <p>{@code isActive = true} only (D5) — same visibility as both sibling reads.
     *
     * @throws ForbiddenException if the actor cannot manage {@code salonId} and does not own
     *                            {@code masterId} themselves
     * @throws NotFoundException  if {@code masterId} does not exist, or exists in a different salon
     */
    @Transactional(readOnly = true)
    public List<MasterServiceResponse> getSalonMasterServices(UUID actorId, UUID salonId, UUID masterId) {
        // Perf MEDIUM (2026-09-11): masterBelongsToSalon(masterId, salonId) and the D3 check below
        // are the SAME existsByIdAndSalonId predicate. On the own-row branch we already prove it
        // here — capture the result and reuse it for D3 instead of re-querying. Stays null (and D3
        // falls back to its own query) on the owner/admin fast path, where this branch never runs —
        // that path's query count (3 for owner, 2 for admin) is unchanged. Only assigned when
        // ownsMasterRow is true: `||` short-circuits masterBelongsToSalon() away otherwise, so a
        // non-owning SALON_MASTER still costs exactly the one findByIdWithUserAndSalon query before
        // its 403, same as before.
        Boolean masterBelongsToSalon = null;
        if (!authz.hasManagementAccess(salonId, actorId)) {
            boolean ownsMasterRow = masterRepository.findByIdWithUserAndSalon(masterId)
                    .map(m -> m.getUser() != null && m.getUser().getId().equals(actorId))
                    .orElse(false);
            if (ownsMasterRow) {
                masterBelongsToSalon = authz.masterBelongsToSalon(masterId, salonId);
            }
            if (!ownsMasterRow || !masterBelongsToSalon) {
                throw new ForbiddenException("Access denied");
            }
        }
        boolean masterExistsInSalon = masterBelongsToSalon != null
                ? masterBelongsToSalon
                : masterRepository.existsByIdAndSalonId(masterId, salonId);
        if (!masterExistsInSalon) {
            throw new NotFoundException("Master not found: " + masterId);
        }

        // Cap matches the sibling reads (getMyServices:944, getMasterServices:917) — this is the
        // established pattern for this repository method, not a decision this phase makes.
        // Switching to a real Pageable (caller-supplied page/size) is a separate, broader change.
        // What IS this phase's concern: the cap is otherwise silent. If a master ever has more
        // than SALON_MASTER_SERVICES_PAGE_CAP active services, this management view would
        // truncate without any signal, so flag the boundary loudly instead of leaving it mute.
        List<MasterServiceResponse> services = masterServiceRepository
                .findByMasterIdAndIsActiveTrueWithGraph(masterId, PageRequest.of(0, SALON_MASTER_SERVICES_PAGE_CAP))
                .stream()
                .map(MasterServiceResponse::from)
                .toList();

        if (services.size() == SALON_MASTER_SERVICES_PAGE_CAP) {
            log.warn(
                    "getSalonMasterServices returned exactly the {} row cap for salonId={} "
                            + "masterId={} — result may be silently truncated",
                    SALON_MASTER_SERVICES_PAGE_CAP, salonId, masterId);
        }

        return services;
    }

    @Transactional
    // Controller applies the role-only fast gate (hasAnyRole SALON_OWNER/INDEPENDENT_MASTER);
    // ownership is enforced here (anti-bug §D/§B14 defense-in-depth) so a future non-HTTP caller
    // cannot bypass the SpEL @PreAuthorize. Reuses the same findOwnerUserId projection the SpEL
    // gate uses — no extra entity load.
    public void deactivateServiceDefinition(UUID actorId, UUID serviceDefId) {
        authz.enforceCanManageServiceDefinition(actorId, serviceDefId);

        // Step 1: identify only the masters that actually use this service definition
        // so the afterCommit eviction targets their cache entries instead of flushing
        // every master (replacing allEntries=true, anti-bug §F).
        List<UUID> affectedMasterIds =
                masterServiceRepository.findMasterIdsByServiceDefinitionId(serviceDefId);

        // Step 2: register the targeted eviction to run after commit so a parallel
        // reader cannot repopulate stale entries between eviction and commit.
        evictMasterServicesCache(affectedMasterIds);

        // Fix MEDIUM-9 PERF: deactivating a service definition also invalidates
        // available-slots cache entries for affected masters. Clients may otherwise see
        // stale slot data for the inactive service until the cache TTL expires.
        // Date-specific eviction is not feasible here (no date context at deactivation
        // time), so eviction is by the Caffeine prefix scan pattern (anti-bug §F rule 6) — but see
        // MEDIUM-3 (Phase-307 perf audit) just below: the direct synchronous call this comment used
        // to describe is gone, because evictBookableFutureSlotsCache's off-thread sweep already
        // covers "available-slots" as a superset (SlotCalculationService#BOOKING_WRITE_CACHES).
        // Duplicating it here scanned the same cache, for the same masters, twice — once
        // synchronously on the committing thread, which is exactly what
        // MasterCachePrefixEvictor's javadoc says must not happen.

        // Fix #6 PERF: deactivation removes this definition from every performing master's bookable set,
        // so the shared master-service-bookable free-slot verdict (gating the booking master-list) is now
        // stale for each affected master — evict it by master prefix, alongside masterServices/available-slots.
        evictBookableFutureSlotsCache(affectedMasterIds);

        // Fix #2/#6 PERF: a SALON-owned definition leaving the bookable set changes the salon catalogue.
        // findSalonOwnerId returns the salon id only for a SALON-owned def (empty → master-owned, which
        // post-Phase-302 means an INDEPENDENT master with no salon catalogue entry to evict — a salon
        // master's definitions are SALON-owned, so this branch already resolves their salon id).
        // Resolved before the UPDATE (deactivation flips is_active, not ownerType).
        evictSalonCatalogAfterCommit(serviceRepository.findSalonOwnerId(serviceDefId).orElse(null));

        // Step 3: execute the update; check after registration so the callback is a
        // no-op when the method throws (transaction rolls back, afterCommit never fires).
        int updated = serviceRepository.deactivateById(serviceDefId);
        if (updated == 0) {
            throw new NotFoundException("Service definition not found: " + serviceDefId);
        }

        // Fix MEDIUM-6 PERF: replace N individual UPDATE round-trips with a single bulk
        // statement — 50 masters = 1 query instead of 50.
        if (!affectedMasterIds.isEmpty()) {
            masterRepository.refreshMinEffectivePriceForAll(affectedMasterIds);
        }
    }

    /**
     * Applies a partial update to a {@link ServiceDefinition}.
     *
     * <p>Only non-null fields in the request are written; null fields are treated as
     * "no change". Ownership is verified by the {@code @PreAuthorize} guard on the
     * controller — callers must enforce the same guard.
     *
     * <p>After the update commits, the {@code masterServices} cache entries for all
     * masters using this definition are evicted (anti-bug §F afterCommit pattern)
     * so that the next read reflects the new data.
     */
    @Transactional
    // Ownership verified by @PreAuthorize("@authz.canManageServiceDefinition") on the controller.
    public ServiceDefinitionResponse updateServiceDefinition(UUID serviceDefId,
            UpdateServiceDefinitionRequest request) {

        ServiceDefinition definition = serviceRepository.findByIdWithServiceType(serviceDefId)
                .orElseThrow(() -> new NotFoundException("Service definition not found: " + serviceDefId));

        // The duplicate guard lives INSIDE applyPatchFields, in its query-only first phase before
        // any setter fires — see the comment there. It cannot run after this call: by then the
        // entity is dirty and its own query would trigger the flush that violates the index.
        applyPatchFields(definition, request);

        // saveAndFlush + translation as the race backstop, mirroring the create paths: if a
        // concurrent transaction commits the same (owner, type) between the guard and this
        // write, the violation surfaces HERE (inside the catch) rather than at commit-time
        // flush outside any handler, so the caller still gets DUPLICATE_SERVICE.
        ServiceDefinition saved = persistDefinition(definition);

        List<UUID> affectedMasterIds =
                masterServiceRepository.findMasterIdsByServiceDefinitionId(serviceDefId);
        evictMasterServicesCache(affectedMasterIds);
        // Name/price/duration/category changes alter the catalogue's rendered content (perf/security #2).
        evictSalonCatalogAfterCommit(salonCatalogIdOf(saved));

        return ServiceDefinitionResponse.from(saved);
    }

    /**
     * Sets or replaces the photo URL for a {@link ServiceDefinition}.
     *
     * <p>Ownership is verified by the {@code @PreAuthorize} guard on the controller.
     * After the update commits, the {@code masterServices} cache entries for all
     * masters using this definition are evicted (anti-bug §F).
     */
    @Transactional
    // Ownership verified by @PreAuthorize("@authz.canManageServiceDefinition") on the controller.
    public ServiceDefinitionResponse updateServicePhoto(UUID serviceDefId, String photoUrl) {
        ServiceDefinition definition = serviceRepository.findByIdWithServiceType(serviceDefId)
                .orElseThrow(() -> new NotFoundException("Service definition not found: " + serviceDefId));

        definition.setPhotoUrl(photoUrl);

        ServiceDefinition saved = serviceRepository.save(definition);

        List<UUID> affectedMasterIds =
                masterServiceRepository.findMasterIdsByServiceDefinitionId(serviceDefId);
        evictMasterServicesCache(affectedMasterIds);
        // A photo change alters the catalogue's rendered content (perf/security #2).
        evictSalonCatalogAfterCommit(salonCatalogIdOf(saved));

        return ServiceDefinitionResponse.from(saved);
    }

    /**
     * Applies PATCH-semantics: only non-null fields in {@code request} are written
     * to {@code definition}. Null fields are ignored — the entity retains its
     * existing value for those attributes.
     *
     * <p>Price block: if ALL four price fields are null the block is treated as absent
     * and the existing pricing is preserved. When any price field is non-null the full
     * mode payload has already been validated by {@code @ServicePriceValid}; this method
     * applies all three price columns atomically.
     *
     * <p><b>Two strictly ordered phases.</b> Every query this PATCH needs runs first
     * ({@link #resolvePatchServiceType}) while the managed entity is still CLEAN; only then
     * does any setter fire. Interleaving them cost a second UPDATE: a PATCH carrying BOTH
     * {@code category} and {@code serviceTypeId} used to set the category, then run the
     * duplicate guard's JPQL over {@code ServiceDefinition} — an overlapping query space, so
     * Hibernate's AUTO flush mode pushed the category-only UPDATE mid-method, the remaining
     * setters re-dirtied the entity, and {@code persistDefinition}'s {@code saveAndFlush}
     * emitted a second UPDATE. That early flush also took the row's write lock sooner than
     * necessary. Keeping the entity clean until the mutate phase collapses it back to one
     * flush and one lock acquisition at the end of the transaction.
     */
    private void applyPatchFields(ServiceDefinition definition,
            UpdateServiceDefinitionRequest request) {

        // ---- Phase 1: resolve + validate. Queries only; the entity stays clean. ----
        ServiceType resolvedServiceType = resolvePatchServiceType(definition, request);

        // ---- Phase 2: mutate. The entity goes dirty here; no query may run past this line. ----
        if (request.category() != null) {
            definition.setCategory(request.category());
        }
        if (resolvedServiceType != null) {
            definition.setServiceType(resolvedServiceType);
        }

        applyNamePatch(definition, request);

        if (request.description() != null) {
            definition.setDescription(request.description());
        }
        if (request.baseDurationMinutes() != null) {
            definition.setBaseDurationMinutes(request.baseDurationMinutes());
        }
        if (request.bufferMinutesAfter() != null) {
            definition.setBufferMinutesAfter(request.bufferMinutesAfter());
        }

        // Price block — treat as atomic: all four null = absent (no change).
        // @ServicePriceValid already guarantees consistency when any field is non-null.
        boolean priceBlockPresent = request.priceType() != null
                || request.price() != null
                || request.priceMin() != null
                || request.priceMax() != null;

        if (priceBlockPresent) {
            applyPriceMode(definition, request.priceType(), request.price(), request.priceMin(), request.priceMax());
        }
    }

    /**
     * Phase 1 of {@link #applyPatchFields}: runs every query the PATCH needs and returns the
     * resolved {@link ServiceType} to assign, or {@code null} to leave the existing type alone.
     *
     * <p>Writes NOTHING to {@code definition} — that is the whole point. Both checks below issue
     * SQL, and Hibernate's AUTO flush mode pushes pending changes whose query space overlaps
     * before executing a query. Two consequences, both load-bearing:
     * <ul>
     *   <li>The duplicate guard MUST see a clean entity. If the colliding {@code service_type_id}
     *       were already assigned, the flush would emit the UPDATE and the V121 index would fire
     *       INSIDE the finder call — the check could never win, and the caller would get the
     *       generic {@code DataIntegrityViolationException} 409 with no {@code data.code} instead
     *       of {@code DUPLICATE_SERVICE}.</li>
     *   <li>A category-only dirty is harmless to the index (category is not part of the key) but
     *       still forces its own UPDATE + row lock, which is the perf half of the same fix.</li>
     * </ul>
     *
     * <p>Validation ordering is preserved from the interleaved version: the category is validated
     * first, then the type is resolved + active-checked + category-matched, so an invalid request
     * still gets its 400 rather than a 409.
     *
     * <p>The effective category — the request's new one, or the entity's existing one when the
     * PATCH does not change it — is computed into a local precisely because the setter has not
     * run yet.
     *
     * @return the type to assign, or {@code null} when the request does not change it
     * @throws BusinessException          (400) unknown/inactive category, or a type that does not
     *                                    belong to the effective category
     * @throws DuplicateServiceException  (409) the owner already offers an active service of this type
     */
    @Nullable
    private ServiceType resolvePatchServiceType(ServiceDefinition definition,
            UpdateServiceDefinitionRequest request) {

        if (request.category() != null) {
            validateCategoryActive(request.category());
        }
        String effectiveCategory =
                request.category() != null ? request.category() : definition.getCategory();

        // Service type (PATCH): null = leave unchanged (never clears).
        if (request.serviceTypeId() == null) {
            // Category-only PATCH: the existing service type is not re-resolved, so re-check that
            // it still belongs to the incoming category. Without this guard a category change
            // silently orphans the existing service type (effective pair inconsistent:
            // definition.category != serviceType.platformCategoryName).
            ServiceType existing = definition.getServiceType();
            if (request.category() != null && existing != null
                    && !Objects.equals(existing.getPlatformCategoryName(), effectiveCategory)) {
                throw new BusinessException(HttpStatus.BAD_REQUEST,
                        "service type does not belong to the selected category");
            }
            return null;
        }

        ServiceType serviceType = resolveServiceType(request.serviceTypeId(), effectiveCategory);

        // excludeId = this row, so re-submitting the type the definition already has is a no-op
        // rather than a self-collision.
        assertNoActiveDuplicate(definition.getOwnerType(), definition.getOwnerId(),
                serviceType, definition.getId());

        return serviceType;
    }

    /**
     * Applies PATCH name semantics:
     * <ul>
     *   <li>{@code name} absent ({@code null}) — leave the existing name unchanged.</li>
     *   <li>{@code name} present and non-blank — overwrite with the supplied value.</li>
     *   <li>{@code name} present but blank (whitespace) — default to the (now effective)
     *       service type's Ukrainian display name. If no service type is set on the entity
     *       after this PATCH, reject with a clear validation error rather than persisting blank.</li>
     * </ul>
     *
     * <p>Called after the service-type patch so {@code definition.getServiceType()} already
     * reflects any type change made by this same request.
     */
    private void applyNamePatch(ServiceDefinition definition, UpdateServiceDefinitionRequest request) {
        if (request.name() == null) {
            return;
        }
        if (!request.name().isBlank()) {
            definition.setName(request.name());
            return;
        }
        // Explicitly blank name — default to the effective service type's Ukrainian name.
        ServiceType effectiveType = definition.getServiceType();
        if (effectiveType == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Name or service type is required");
        }
        definition.setName(effectiveType.getNameUk());
    }

    /**
     * Sets the three price columns on the entity from the validated mode payload.
     *
     * <p>Invariant (enforced upstream by {@code @ServicePriceValid}):
     * <ul>
     *   <li>FIXED: {@code price} non-null, stored in {@code basePrice}; {@code priceMax} = null.</li>
     *   <li>RANGE: {@code priceMin} stored in {@code basePrice}, {@code priceMax} non-null.
     *       {@code base_price} = floor, so {@code masters.min_effective_price} (V58) remains correct.</li>
     * </ul>
     */
    private void applyPriceMode(ServiceDefinition definition, PriceType priceType,
                                 BigDecimal price, BigDecimal priceMin,
                                 BigDecimal priceMax) {
        definition.setPriceType(priceType);
        if (priceType == PriceType.FIXED) {
            definition.setBasePrice(price);
            definition.setPriceMax(null);
        } else {
            // RANGE: base_price = minimum (floor), price_max = ceiling.
            // min_effective_price = MIN(COALESCE(price_override, base_price)) — still correct.
            definition.setBasePrice(priceMin);
            definition.setPriceMax(priceMax);
        }
    }

    @Transactional(readOnly = true)
    public List<CatalogCategoryResponse> getCategories() {
        return catalogCategoryLookup.getAll();
    }

    /**
     * A salon's public, bookable service catalog grouped by category (Phase 13.6,
     * {@code GET /salons/{salonId}/services}).
     *
     * <p><b>Free-slot bookability gate (Phase 23.x — CRITICAL fix).</b> A service appears here
     * only if at least one performing master is actually BOOKABLE — active, in this salon, actively
     * assigned, and with ≥1 free future slot after existing bookings are subtracted. This is the same
     * verdict the booking master-list uses ({@code BookingMasterService#getBookableMasters}), so the
     * catalogue and the master picker can never disagree. Candidate assignments are loaded once
     * ({@code findBookableAssignmentsBySalon}), grouped by master, and each master's schedule + booking
     * window is resolved ONCE by {@code SlotCalculationService#filterBookableAssignments} — O(distinct
     * masters) heavy loads, not O(services × masters).
     *
     * <p><b>Caching (perf/security #2).</b> Cached in {@code salon-service-catalog} keyed on
     * {@code salonId} (60-sec TTL, {@code sync=true}) — this is a {@code permitAll} endpoint whose body
     * runs an O(distinct masters) free-slot compute per hit (schedule resolve + booking load per master),
     * a DB-amplification / stampede surface without a result cache. {@code sync=true} collapses the
     * thundering herd when a popular salon's entry expires (§F-7). Explicit eviction runs afterCommit via
     * {@link SalonCatalogCacheEvictor} from every write that can flip the bookable-service set: a
     * booking / cancellation / schedule change on any master in the salon (evicted at those write sites
     * by the master's salon id) and this service's own definition mutations (create / assign / update /
     * photo / deactivate). The 60-sec TTL is only a backstop.
     *
     * <p>The result is the WHOLE catalog (not paginated) so the mobile client can group it by
     * category client-side; the candidate query is bounded by salon scope, not a caller-supplied size.
     */
    @Transactional(readOnly = true)
    @Cacheable(value = "salon-service-catalog", key = "#salonId", sync = true)
    public SalonServiceCatalogResponse getSalonServiceCatalog(UUID salonId) {
        List<MasterServiceAssignment> candidates =
                masterServiceRepository.findBookableAssignmentsBySalon(salonId);

        if (candidates.isEmpty()) {
            return new SalonServiceCatalogResponse(List.of());
        }

        List<ServiceDefinition> definitions = bookableDefinitions(candidates);
        if (definitions.isEmpty()) {
            return new SalonServiceCatalogResponse(List.of());
        }

        CategoryOrderAndNames orderAndNames = buildCategoryOrderAndNames();
        Map<String, Integer> categoryOrder = orderAndNames.order();
        Map<String, String> categoryDisplayNames = orderAndNames.displayNames();

        Map<String, List<ServiceDefinition>> byCategory = definitions.stream()
                .collect(Collectors.groupingBy(
                        sd -> Objects.requireNonNullElse(sd.getCategory(), ""),
                        LinkedHashMap::new,
                        Collectors.toList()));

        List<SalonServiceCategoryGroup> categories = byCategory.entrySet().stream()
                .sorted(Comparator
                        .<Map.Entry<String, List<ServiceDefinition>>>comparingInt(
                                e -> categoryOrder.getOrDefault(e.getKey(), Integer.MAX_VALUE))
                        .thenComparing(Map.Entry::getKey))
                .map(e -> new SalonServiceCategoryGroup(
                        e.getKey(),
                        categoryDisplayNames.getOrDefault(e.getKey(), e.getKey()),
                        e.getValue().size(),
                        e.getValue().stream().map(ServiceDefinitionResponse::from).toList()))
                .toList();

        return new SalonServiceCatalogResponse(categories);
    }

    /**
     * Groups the candidate assignments by master, runs the batched free-slot gate once per master
     * ({@code SlotCalculationService#filterBookableAssignments} — one schedule resolve + one booking
     * load per master), and returns the distinct {@link ServiceDefinition}s that at least one bookable
     * master performs, sorted by {@code (category, name)} so the caller's category grouping keeps the
     * same within-group ordering the previous {@code findBookableServicesBySalon} query produced.
     *
     * <p><b>Phase 305 D1 — the free-slot gate applied here is DELIBERATE contract, not a bug.</b> A
     * candidate assignment is kept only when {@code filterBookableAssignments} finds its master a
     * free future slot for the service's effective duration; a master with NO working hours
     * configured resolves zero effective schedule days and is therefore filtered out entirely, so
     * NONE of their services reach the salon catalogue — even though Phases 302/303 make those
     * definitions genuinely {@code SALON}-owned. {@code GET /salons/{salonId}/services} answers
     * "what can a client book here right now?", not "what does this salon's staff list on paper?",
     * and this is the call frame where that answer is enforced.
     *
     * <p><b>Do not "fix" this.</b> Any future finding titled "a master's services are missing from
     * the salon catalogue" must first check whether that master has working hours configured — see
     * {@code SalonCatalogueVisibilityIT#should_notBeVisible_when_masterHasNoWorkingHours_pinningD1AsDeliberate}
     * (Phase 305 D1/D2 condition 5), which pins exactly this behaviour as deliberate.
     */
    private List<ServiceDefinition> bookableDefinitions(List<MasterServiceAssignment> candidates) {
        Map<UUID, List<MasterServiceAssignment>> byMaster = candidates.stream()
                .collect(Collectors.groupingBy(a -> a.getMaster().getId()));

        Map<UUID, ServiceDefinition> bookableById = new LinkedHashMap<>();
        for (Map.Entry<UUID, List<MasterServiceAssignment>> entry : byMaster.entrySet()) {
            for (MasterServiceAssignment msa :
                    slotCalculationService.filterBookableAssignments(entry.getKey(), entry.getValue())) {
                bookableById.putIfAbsent(msa.getServiceDefinition().getId(), msa.getServiceDefinition());
            }
        }

        return bookableById.values().stream()
                .sorted(Comparator
                        .comparing((ServiceDefinition sd) -> Objects.requireNonNullElse(sd.getCategory(), ""))
                        .thenComparing(ServiceDefinition::getName))
                .toList();
    }

    /**
     * {@code buildCategoryOrderAndNames()}'s return shape — the ordinal-position map used to
     * sort {@link #getSalonServiceCatalog} groups, and the sibling {@code name -> displayName}
     * map used to resolve the human-readable category header. Both are derived from a single
     * pass over the same {@link PlatformCategoryOrderLookup#getApprovedActive()} list so the
     * cached lookup is not queried twice per request.
     */
    private record CategoryOrderAndNames(Map<String, Integer> order, Map<String, String> displayNames) {
    }

    /**
     * Builds a {@code category name -> ordinal position} map, and a sibling {@code category
     * name -> displayName} map, from the approved+active {@link PlatformCategory} rows, in the
     * same display order the category picker already uses ({@link
     * PlatformCategoryRepository#findApprovedActive}, {@code ORDER BY displayName} —
     * {@link PlatformCategory} carries no explicit {@code sortOrder} column). A {@code
     * ServiceDefinition.category} value with no entry in these maps (inactive/legacy/unknown
     * category) sorts alphabetically AFTER every known category, and falls back to the raw
     * category slug itself as its display name, in {@link #getSalonServiceCatalog}.
     *
     * <p>Deliberately NOT {@link #getCategories()} / {@link CatalogCategoryLookup}: that
     * returns the separate, orphaned "System A" {@code service_categories} table keyed by
     * {@code nameUk}/{@code nameEn} display strings (e.g. {@code "Nails"}) — those never
     * match {@code ServiceDefinition.category}, which stores the live {@link PlatformCategory
     * #getName()} canonical code (e.g. {@code "MANICURE"}). Using {@code getCategories()}
     * here would silently never match anything, degrading every group to alphabetical order
     * and its raw slug as the display name.
     *
     * <p>Delegates the {@code findApprovedActive()} query through {@link
     * PlatformCategoryOrderLookup} (a separate {@code @Cacheable} bean, 60-min TTL) instead of
     * calling {@link #platformCategoryRepository} directly: this data is static,
     * admin-approval-gated reference data identical across every request, so re-querying it on
     * every public {@link #getSalonServiceCatalog} hit was pure waste. A same-class
     * {@code @Cacheable} method would not intercept via the AOP proxy on this self-invocation
     * (same caveat documented on {@link #searchServiceTypes}), hence the separate bean.
     */
    private CategoryOrderAndNames buildCategoryOrderAndNames() {
        List<PlatformCategory> approved = platformCategoryOrderLookup.getApprovedActive();
        Map<String, Integer> order = new LinkedHashMap<>();
        Map<String, String> displayNames = new LinkedHashMap<>();
        for (int i = 0; i < approved.size(); i++) {
            PlatformCategory category = approved.get(i);
            order.put(category.getName(), i);
            displayNames.put(category.getName(), category.getDisplayName());
        }
        return new CategoryOrderAndNames(order, displayNames);
    }

    @Transactional(readOnly = true)
    public List<ServiceTypeResponse> searchServiceTypes(@Nullable UUID categoryId, @Nullable String q) {
        if (categoryId != null) {
            boolean exists = catalogCategoryLookup.getAll().stream()
                    .anyMatch(c -> c.id().equals(categoryId));
            if (!exists) throw new NotFoundException("Category not found");
        }
        // Intentional duplication of the controller's @Size(min=3) constraint: this guard
        // defends non-HTTP callers (internal services, tests, future programmatic callers)
        // where the Bean Validation boundary is not active. Removing it would silently allow
        // short queries to exhaust cache slots via direct service invocation.
        boolean useSearch = q != null && q.strip().length() >= 3;

        if (useSearch) {
            // Delegate through serviceTypeSearchService (a separate Spring bean) so that
            // the @Cacheable proxy intercept is active. A direct this.method() call would
            // bypass the AOP proxy and make caching inert.
            return serviceTypeSearchService.searchByName(q.strip().toLowerCase(Locale.ROOT), categoryId);
        }
        return serviceTypeLookup.getByCategory(categoryId).stream()
                .map(ServiceTypeResponse::from)
                .toList();
    }

    /**
     * Returns active service types belonging to the given platform-category name slug,
     * ordered by Ukrainian name ascending.
     *
     * <p>An unknown or inactive {@code categoryName} value returns an empty list (not 404)
     * — the mobile picker treats it as "no types available for this category".
     *
     * <p>No caching: the {@code service_types} catalog is small and static, and a
     * dedicated per-{@code categoryName} cache entry would need eviction on every
     * {@code platform_categories} or {@code service_types} mutation. The query is
     * cheap (partial B-tree index from V73) and the call rate low enough that the
     * cache overhead would exceed the benefit.
     *
     * @param categoryName canonical uppercase platform-category name slug
     *                     (e.g. {@code EYELASH}, {@code HAIR})
     */
    @Transactional(readOnly = true)
    public List<PlatformServiceTypeResponse> findServiceTypesByPlatformCategory(String categoryName) {
        // Intentional duplication of the controller's @NotBlank/@Size constraint: this guard
        // defends non-HTTP callers (internal services, tests, future programmatic callers)
        // where the Bean Validation boundary is not active.
        if (categoryName == null || categoryName.strip().isEmpty()) {
            return List.of();
        }
        return serviceTypeRepository.findActiveByPlatformCategoryName(categoryName)
                .stream()
                .map(PlatformServiceTypeResponse::from)
                .toList();
    }

    public void suggestServiceType(SuggestServiceTypeRequest request, UUID requestedByUserId) {
        // Resolve/validate the System-B category-name slug against platform_categories
        // (active + APPROVED) BEFORE persisting — an unknown or inactive slug yields a
        // clean 400 instead of persisting + emailing the admin a bogus suggestion
        // (Phase 16.7 guard, preserved).
        validateCategoryActive(request.categoryName());

        // Phase 16.8: no longer fire-and-forget. Delegate to the suggestion service,
        // which persists a PENDING service_type_suggestion row carrying a hashed
        // single-use token and emails the admin a token-authenticated review link.
        // Field escaping is handled by the suggestion's Thymeleaf email template
        // (auto-escaped), so no manual sanitize is needed here.
        serviceTypeSuggestionService.submitSuggestion(
                request.categoryName(), request.name(), request.description(), requestedByUserId);
    }

    /**
     * Evicts the given master IDs from the "masterServices" cache.
     *
     * <p>When a Spring transaction is active (the normal production path), the eviction is
     * deferred to {@code afterCommit} so a concurrent reader cannot repopulate the cache
     * with a pre-commit DB snapshot. When no transaction is active (e.g., in unit tests or
     * programmatic non-transactional callers), the eviction runs immediately — same net
     * effect as the former {@code @CacheEvict} annotation.
     */
    private void evictMasterServicesCache(List<UUID> masterIds) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            var cache = cacheManager.getCache("masterServices");
                            if (cache != null) masterIds.forEach(cache::evict);
                        }
                    }
            );
        } else {
            var cache = cacheManager.getCache("masterServices");
            if (cache != null) masterIds.forEach(cache::evict);
        }
    }

    /**
     * Evicts the {@code salon-service-catalog} entry for the given salon after commit (perf/security #2).
     * A {@code null} salonId (a master-owned, salon-less definition) is a no-op — an independent master
     * owns no salon catalogue entry. Deferred to {@code afterCommit} so a parallel reader cannot
     * repopulate the public cache with a pre-commit snapshot (§F rule 2).
     */
    /**
     * The salon-catalogue key for a definition: its {@code ownerId} when SALON-owned (the ownerId IS the
     * salon id), else {@code null} (a master-owned definition owns no salon catalogue entry).
     */
    private static UUID salonCatalogIdOf(ServiceDefinition definition) {
        return definition.getOwnerType() == OwnerType.SALON ? definition.getOwnerId() : null;
    }

    private void evictSalonCatalogAfterCommit(UUID salonId) {
        if (salonId == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            salonCatalogCacheEvictor.evict(salonId);
                        }
                    });
        } else {
            salonCatalogCacheEvictor.evict(salonId);
        }
    }

    /**
     * Evicts the {@code master-service-bookable} verdict for each affected master after commit
     * (perf #6). Deactivating a definition removes it from every performing master's bookable set, so
     * the shared free-slot verdict that gates the booking master-list must be invalidated (by master
     * prefix — {@link com.beautica.booking.service.SlotCalculationService#evictMasterAvailabilityCaches}),
     * alongside {@code masterServices} and {@code available-slots}.
     */
    private void evictBookableFutureSlotsCache(List<UUID> masterIds) {
        if (masterIds.isEmpty()) {
            return;
        }
        Runnable task = () -> masterIds.forEach(slotCalculationService::evictMasterAvailabilityCaches);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            task.run();
                        }
                    });
        } else {
            task.run();
        }
    }

    /**
     * Rejects a write that would leave {@code (ownerType, ownerId)} with two ACTIVE services of
     * the same {@link ServiceType} — the locked product rule "one active service per owner per
     * service type, <em>regardless of price or duration</em>".
     *
     * <p>This is the friendly path only. The real guarantee is the partial unique index
     * {@code ux_service_def_owner_service_type_active} (V121); this pre-check exists so the
     * common case yields a branchable {@code DUPLICATE_SERVICE} 409 naming the existing service
     * instead of a bare constraint violation. Being a read-then-write check it is TOCTOU-prone,
     * which {@link #persistDefinition} closes on every write path.
     *
     * <p>The index is partial on {@code is_active = true} because deletion is soft
     * ({@link ServiceRepository#deactivateById}) — a previously deleted service must stay
     * re-creatable, and the query mirrors that with its own {@code isActive = true} predicate.
     *
     * <p><b>Accepted cost: one unconditional SELECT on the success path.</b> Re-examined and
     * KEPT by the Phase 26.8/26.9 audit (backend-perf, "Accept"): the pre-check stays because the
     * catch-side alternative trades one indexed equality lookup on an infrequent write for a
     * second pooled connection on the failing one. Single creates and any PATCH carrying
     * {@code serviceTypeId} pay this query every time purely so the rare conflict can carry
     * {@code existingServiceDefId} for the client's deep-link. The obvious lazy alternative —
     * skip the pre-check, let the index fire, look the id up in the catch — does NOT work: a
     * constraint violation aborts the Postgres transaction, so no further statement can run in
     * that catch without a {@code REQUIRES_NEW} sub-transaction (an extra connection from the
     * pool, plus its own commit, on a path that is meant to be the cheap one). A single indexed
     * equality lookup on an infrequent write path is the cheaper trade, so it stays; do not
     * re-open without a measurement showing this SELECT on a hot path. The bulk path is different —
     * there the per-item cost multiplied by 100 and by forced flushes, hence
     * {@link #assertNoActiveDuplicatesInBatch}.
     *
     * @param excludeId the row being updated (so a PATCH cannot collide with itself), or
     *                  {@code null} on a create path
     * @throws DuplicateServiceException (409) when an active service of this type already exists
     */
    private void assertNoActiveDuplicate(OwnerType ownerType, UUID ownerId,
            @Nullable ServiceType serviceType, @Nullable UUID excludeId) {

        // Null-safe: service_type_id is NOT NULL since V111 and every create path resolves a
        // non-null type, so this is unreachable in practice — but a null here must never become
        // an NPE 500. The DB index remains the backstop.
        if (serviceType == null || serviceType.getId() == null) {
            return;
        }

        serviceRepository
                .findActiveDuplicateId(ownerType, ownerId, serviceType.getId(), excludeId)
                .ifPresent(existingId -> {
                    throw new DuplicateServiceException(serviceType.getNameUk(), existingId);
                });
    }

    /**
     * Batch form of {@link #assertNoActiveDuplicate} for the additive bulk-create path: ONE
     * query covering every item, instead of one per item.
     *
     * <p><b>Why batched.</b> The request accepts up to 100 items, and the per-item form issued
     * 100 serialized SELECTs — regressing the explicit one-query batching the neighbouring steps
     * ({@link #resolveBulkServiceTypes}, {@link #validateBulkCategoriesActive}) already establish.
     * It also cost more than the SELECTs themselves: each JPQL query triggers a Hibernate AUTO
     * flush of the pending inserts, so the per-item guard is what forced the per-item flush that
     * defeated JDBC insert batching. Batching the guard and batching the flush only pay off
     * together.
     *
     * <p><b>This is the only cross-request conflict guard the bulk path has.</b> Bulk create is
     * additive — there is no "master must have zero services" precondition to lean on — so every
     * collision with what the owner already offers is caught here. The guard is definition-level,
     * exactly like the V121 unique index it front-runs, which means it also covers the awkward
     * case of an active definition carrying no active assignment: invisible in the master's menu,
     * still a collision at INSERT. Catching it here turns that into a clean 409 naming the
     * offending item instead of a 500 at flush. {@link #flushBulkBatch} still translates a raced
     * V121 violation as the last line of defence.
     *
     * <p>Reported collision is the FIRST in <em>request order</em>, not in result order: the
     * query's row order is unspecified, and blaming a later item for an earlier item's conflict
     * would make the error non-deterministic across identical requests.
     *
     * @throws DuplicateServiceException (409) naming the first item whose type is already taken
     */
    private void assertNoActiveDuplicatesInBatch(OwnerType ownerType, UUID ownerId,
            List<BulkServiceItemRequest> items, Map<UUID, ServiceType> typesById) {

        if (typesById.isEmpty()) {
            return;
        }

        Map<UUID, UUID> existingDefIdByTypeId = serviceRepository
                .findActiveDuplicateTypeIds(ownerType, ownerId, typesById.keySet())
                .stream()
                .collect(Collectors.toMap(
                        ActiveDuplicateProjection::serviceTypeId,
                        ActiveDuplicateProjection::serviceDefinitionId,
                        (first, second) -> first));

        if (existingDefIdByTypeId.isEmpty()) {
            return;
        }

        for (BulkServiceItemRequest item : items) {
            UUID existingId = existingDefIdByTypeId.get(item.serviceTypeId());
            if (existingId != null) {
                ServiceType type = typesById.get(item.serviceTypeId());
                throw new DuplicateServiceException(
                        type != null ? type.getNameUk() : null, existingId);
            }
        }
    }

    /**
     * Phase 302 D2/D4 — the SALON branch's whole read-then-write critical section, in ONE query
     * for the whole batch: it both rejects a genuine per-master conflict (409) and returns the
     * salon definitions the batch should reuse.
     *
     * <p><b>Why the SALON branch cannot use the owner-level guard.</b> Under D1 the owner is the
     * salon, shared by every master in it. Asking "does the OWNER already offer this type" would
     * reject the salon's second master from ever offering «Манікюр» — the exact opposite of the
     * phase's goal. The salon already offering a type is the REUSE path; the only genuine
     * conflict left is <em>this master</em> already performing it, whose backing invariant is
     * {@code master_services}' {@code UNIQUE (master_id, service_def_id)}.
     *
     * <p><b>One round-trip, not three (audit LOW-3).</b> This previously ran a per-master
     * assignment query, then an owner-level definition query, then a {@code findAllById} that
     * merely re-fetched definitions the second query had already joined. Once the advisory lock
     * became salon-keyed (audit HIGH-2) that serialized window is salon-wide, so three round-trips
     * inside it multiply across every concurrent master setup in the salon.
     * {@link ServiceRepository#findSalonBulkSetupCandidates} answers all three questions in one
     * batched (never per-item) statement.
     *
     * <p><b>Salon-scoped (audit HIGH-1).</b> The deleted per-master finder matched on
     * {@code master_id} alone, so a master ROTATED between salons — rotation moves
     * {@code masters.salon_id} and never touches {@code master_services} — kept ACTIVE assignments
     * to the SOURCE salon's definitions and was refused in the destination with a 409 disclosing a
     * source-salon {@code existingServiceDefId}. Scoping lives in the query's owner predicate; its
     * {@code INDEPENDENT_MASTER} arm keeps the ~60 legacy master-owned rows (phase 303 backfills
     * them) firing as conflicts.
     *
     * @return type id → the salon's reusable ACTIVE definition (plus, Phase 307 D6, type id → any
     *         INACTIVE assignment of the master's own that must be reactivated rather than
     *         re-inserted); empty when the salon offers none of the batch's types
     * @throws DuplicateServiceException (409) naming the first item this master already offers
     */
    private SalonBulkCandidateResolution resolveSalonBulkCandidates(
            UUID masterId, UUID salonId,
            List<BulkServiceItemRequest> items, Map<UUID, ServiceType> typesById) {

        if (typesById.isEmpty()) {
            return new SalonBulkCandidateResolution(Map.of(), Map.of());
        }

        List<SalonBulkSetupCandidate> candidates = serviceRepository
                .findSalonBulkSetupCandidates(salonId, masterId, typesById.keySet());

        assertMasterDoesNotAlreadyOffer(candidates, items, typesById);
        Map<UUID, ServiceDefinition> reusableByTypeId = reusableSalonDefinitions(candidates);
        Map<UUID, UUID> reactivateAssignmentIdByTypeId = reactivatableAssignmentIdByTypeId(candidates);
        return new SalonBulkCandidateResolution(reusableByTypeId, reactivateAssignmentIdByTypeId);
    }

    /**
     * Bundles {@link #resolveSalonBulkCandidates}'s two answers: which definitions to reuse, and
     * which of the master's own INACTIVE assignments must be reactivated rather than re-inserted
     * (Phase 307 D6). A small local record rather than two separately-threaded maps or a third
     * round-trip — both come off the same {@link SalonBulkSetupCandidate} list.
     */
    private record SalonBulkCandidateResolution(
            Map<UUID, ServiceDefinition> reusableByTypeId,
            Map<UUID, UUID> reactivateAssignmentIdByTypeId) {
    }

    /**
     * Phase 307 D6 — type id → the master's own INACTIVE {@code master_services} row id for the
     * salon's reused definition of that type, for every candidate the master previously
     * unassigned. Only {@code ownerType = SALON} candidates qualify, mirroring
     * {@link #reusableSalonDefinitions}: an {@code INDEPENDENT_MASTER}-owned legacy definition is a
     * conflict signal only, never a reuse/reactivation target.
     */
    private static Map<UUID, UUID> reactivatableAssignmentIdByTypeId(
            List<SalonBulkSetupCandidate> candidates) {

        Map<UUID, UUID> reactivateByTypeId = new LinkedHashMap<>();
        for (SalonBulkSetupCandidate candidate : candidates) {
            if (candidate.definition().getOwnerType() == OwnerType.SALON
                    && candidate.hasInactiveAssignment()) {
                reactivateByTypeId.put(candidate.serviceTypeId(), candidate.masterAssignmentId());
            }
        }
        return reactivateByTypeId;
    }

    /**
     * Phase 312 D3/D9 — resolves the FULL per-master band a reused item writes onto
     * {@code master_services}, superseding the Phase 302 guard that used to REJECT a shape
     * differing from the reused definition's ({@code assertReusableShapesAreRepresentable} /
     * {@code isShapeRepresentableOnAssignment}, retired). V165 (Phase 311) gives
     * {@code master_services} its own shape and ceiling, so every shape the item can express is
     * now representable — the reuse branch stores it instead of discarding it:
     * <ul>
     *   <li>{@code FIXED 500} definition + {@code RANGE 400–900} item → the master's OWN
     *       {@code RANGE 400–900} band is stored (previously flattened to {@code FIXED 400}).</li>
     *   <li>{@code RANGE 400–900} definition + {@code FIXED 600} item → the master's OWN
     *       {@code FIXED 600} is stored (previously rendered {@code 600–900}, a ceiling nobody
     *       set).</li>
     *   <li>{@code RANGE 400–900} definition + {@code RANGE 500–800} item → the master's OWN
     *       ceiling {@code 800} is stored (previously discarded in favour of the salon's 900).</li>
     * </ul>
     *
     * <p><b>Inherited when the item's band matches the reused definition's, own band otherwise
     * (Phase 311 D2 — all-or-nothing).</b> An item whose type, floor AND (for RANGE) ceiling all
     * equal the reused definition's needs no override at all: leaving the three columns
     * {@code NULL} means this assignment keeps tracking the shared definition, exactly as a plain
     * FIXED-against-FIXED reuse always has. Any other item is stored as a complete, independent
     * band — never a partial one, which {@link MasterServiceBand#isLegal} guards as
     * defense-in-depth (D2's one-truth-table property, Phase 312's whole point: this is the SAME
     * validator {@code updateMasterServiceBand} and {@link #assignServiceToMaster} defer to, not
     * a second copy of the comparisons).
     *
     * <p>The floor/ceiling comparison uses {@code compareTo}, never {@code equals}: {@code 900}
     * and {@code 900.00} are the same money and must not be read as a diverging band.
     *
     * <p>Pure in-memory — {@code reused} was already loaded by
     * {@link ServiceRepository#findSalonBulkSetupCandidates} in this transaction, so this adds no
     * round-trip inside the salon-wide serialized window.
     *
     * @param item   the batch item being written; already internally coherent — {@code @Valid}'s
     *               {@code @ServicePriceValid} on {@link BulkServiceItemRequest} guarantees a
     *               FIXED item carries only {@code price} and a RANGE item carries a STRICTLY
     *               increasing {@code priceMin < priceMax} pair — {@code ServicePriceValidator}
     *               rejects {@code priceMax == priceMin} — before this method ever runs
     * @param reused the salon's existing active definition this item reuses
     * @return the triple to write onto the new/reactivated {@link MasterServiceAssignment}; all
     *         three {@code null} for Inherited, or all three populated for an own band
     */
    private static ResolvedBand resolveBulkReuseBand(BulkServiceItemRequest item, ServiceDefinition reused) {
        if (bulkItemBandMatchesDefinition(item, reused)) {
            return ResolvedBand.INHERITED;
        }

        BigDecimal floor = item.priceType() == PriceType.FIXED ? item.price() : item.priceMin();
        BigDecimal ceiling = item.priceType() == PriceType.RANGE ? item.priceMax() : null;

        // Assertion, not a guard — and no longer a vacuous one. @ServicePriceValid on
        // BulkServiceItemRequest and MasterServiceBand.isLegal now agree on the strict (>)
        // floor/ceiling comparison (Phase 312 D8), so this fires only if the two truth tables
        // genuinely drift, or if the field translation above (definition-shaped priceMin/priceMax
        // → band-shaped price/priceMax) stops being faithful. Either is a bug, not a bad request,
        // hence IllegalStateException rather than a 400.
        if (!MasterServiceBand.isLegal(item.priceType(), floor, ceiling)) {
            throw new IllegalStateException(
                    "Bulk item band failed the MasterServiceBand invariant after passing "
                            + "@ServicePriceValid — validator drift: " + item);
        }
        return new ResolvedBand(item.priceType(), floor, ceiling);
    }

    /**
     * True iff {@code item}'s full band (shape, floor, and — for RANGE — ceiling) is
     * byte-for-byte what {@code reused} already offers, i.e. the assignment needs no override at
     * all and can stay Inherited (Phase 311 D2).
     */
    private static boolean bulkItemBandMatchesDefinition(BulkServiceItemRequest item, ServiceDefinition reused) {
        if (item.priceType() != reused.getPriceType()) {
            return false;
        }
        BigDecimal itemFloor = item.priceType() == PriceType.FIXED ? item.price() : item.priceMin();
        BigDecimal reusedFloor = reused.getBasePrice();
        if (itemFloor == null || reusedFloor == null || itemFloor.compareTo(reusedFloor) != 0) {
            return false;
        }
        if (item.priceType() != PriceType.RANGE) {
            return true;
        }
        BigDecimal itemCeiling = item.priceMax();
        BigDecimal reusedCeiling = reused.getPriceMax();
        return itemCeiling != null && reusedCeiling != null
                && itemCeiling.compareTo(reusedCeiling) == 0;
    }

    /**
     * The band {@link #resolveBulkReuseBand} decides to write onto a reused-definition
     * assignment: either {@link #INHERITED} (all three {@code master_services} band columns
     * {@code NULL}) or a fully-specified own band — never partial (Phase 311 D2).
     */
    private record ResolvedBand(PriceType priceType, BigDecimal price, BigDecimal priceMax) {
        private static final ResolvedBand INHERITED = new ResolvedBand(null, null, null);
    }

    /**
     * Phase 302 D4 — the per-MASTER conflict half of {@link #resolveSalonBulkCandidates}.
     *
     * <p>Keyed on the SERVICE TYPE, not on the definition id: the client picks a type, and a
     * master must not end up with two rows for the same type even if two owners held a definition
     * for it. Checking {@code serviceDefId} instead would be satisfied by the assignment unique
     * constraint alone and would not express this rule.
     *
     * <p>Reports the FIRST collision in <em>request</em> order — result order is unspecified, and
     * blaming a later item for an earlier item's conflict would make the error non-deterministic
     * across identical requests. The reported {@code existingServiceDefId} prefers the SALON-owned
     * row when a legacy master-owned definition for the same type also exists: that is the row the
     * caller is authorised for and can deep-link to.
     *
     * <p>Like every read-then-write guard here it is TOCTOU-prone; the assignment unique key is
     * the actual guarantee, and the advisory lock this runs under makes ordinary contention
     * produce this clean, item-naming 409 rather than a raced constraint violation.
     *
     * @throws DuplicateServiceException (409) naming the first item this master already offers
     */
    private static void assertMasterDoesNotAlreadyOffer(List<SalonBulkSetupCandidate> candidates,
            List<BulkServiceItemRequest> items, Map<UUID, ServiceType> typesById) {

        Map<UUID, UUID> conflictingDefIdByTypeId = new LinkedHashMap<>();
        for (SalonBulkSetupCandidate candidate : candidates) {
            if (!candidate.assignedToMaster()) {
                continue;
            }
            boolean salonOwned = candidate.definition().getOwnerType() == OwnerType.SALON;
            if (salonOwned || !conflictingDefIdByTypeId.containsKey(candidate.serviceTypeId())) {
                conflictingDefIdByTypeId.put(
                        candidate.serviceTypeId(), candidate.definition().getId());
            }
        }

        for (BulkServiceItemRequest item : items) {
            UUID existingId = conflictingDefIdByTypeId.get(item.serviceTypeId());
            if (existingId != null) {
                ServiceType type = typesById.get(item.serviceTypeId());
                throw new DuplicateServiceException(
                        type != null ? type.getNameUk() : null, existingId);
            }
        }
    }

    /**
     * Phase 302 D2 — the REUSE half of {@link #resolveSalonBulkCandidates}: the salon's own ACTIVE
     * definitions, keyed by service-type id, so {@link #createSingleFromBulkItem} can attach this
     * master's assignment to them instead of minting a second definition V121 would refuse.
     *
     * <p>Only {@code ownerType = SALON} rows qualify. A legacy {@code INDEPENDENT_MASTER}-owned
     * definition in the candidate set is a CONFLICT signal only (see
     * {@link #assertMasterDoesNotAlreadyOffer}); reusing it would leave the salon's catalogue
     * without the row the catalogue query requires. V121 admits at most one active SALON-owned
     * definition per {@code (salonId, typeId)}, so at most one row can land per key.
     *
     * <p>The definitions arrive already hydrated from the candidate query — the assignment insert
     * needs the managed entity, D3's override comparison needs its base price/duration, and the
     * response DTO renders it. Their {@code serviceType} association resolves from the persistence
     * context ({@code resolveBulkServiceTypes} loaded exactly these types in this transaction), so
     * no N+1 follows.
     */
    private static Map<UUID, ServiceDefinition> reusableSalonDefinitions(
            List<SalonBulkSetupCandidate> candidates) {

        Map<UUID, ServiceDefinition> reusableByTypeId = new LinkedHashMap<>();
        for (SalonBulkSetupCandidate candidate : candidates) {
            if (candidate.definition().getOwnerType() == OwnerType.SALON) {
                reusableByTypeId.put(candidate.serviceTypeId(), candidate.definition());
            }
        }
        return reusableByTypeId;
    }

    /**
     * Phase 302 D3 — the {@code master_services.duration_override_minutes} for a reused
     * definition: the batch item's duration when it differs from the salon definition's
     * {@code base_duration_minutes}, else {@code null}.
     */
    @Nullable
    private static Integer overrideDurationFor(BulkServiceItemRequest item, ServiceDefinition reused) {
        Integer itemDuration = item.durationMinutes();
        if (itemDuration == null || itemDuration == reused.getBaseDurationMinutes()) {
            return null;
        }
        return itemDuration;
    }

    /**
     * Flushes a bulk batch's pending inserts in a single round-trip, translating a V121 violation
     * into {@link DuplicateServiceException} exactly as {@link #persistDefinition} does for the
     * paths that flush through {@code saveAndFlush}.
     *
     * <p>This is the batch counterpart of {@code saveAndFlush}: the inserts must reach the DB
     * inside this try block, otherwise a violation would surface at commit-time flush — outside
     * any catch — and degrade to the generic {@code DataIntegrityViolationException} 409 with no
     * {@code data.code} for the client to branch on. With {@code hibernate.jdbc.batch_size=50}
     * and {@code order_inserts=true} the whole batch leaves as a handful of round-trips.
     *
     * <p><b>Carries no service name or id</b>, unlike every other {@code DUPLICATE_SERVICE}
     * site. Reaching here means {@link #assertNoActiveDuplicatesInBatch} passed and a concurrent
     * transaction won the race in between; the flush reports the constraint, not which of the N
     * queued rows lost, and the Postgres transaction is aborted at that point so nothing further
     * can be queried to find out. The client still gets the stable {@code DUPLICATE_SERVICE}
     * code, which is what it branches on.
     */
    private void flushBulkBatch() {
        flushTranslatingDuplicateViolation(null);
    }

    /**
     * The one place a deferred flush is turned into a branchable {@code DUPLICATE_SERVICE} 409 —
     * shared by {@link #flushBulkBatch} and by {@code addIndependentMasterService}, which defers
     * its flush until after the assignment insert so both statements leave in one flush cycle.
     *
     * <p>{@code EntityManager#flush} is persistence-context-wide, not repository-scoped, so
     * flushing through {@code serviceRepository} also emits the pending
     * {@link MasterServiceAssignment} insert — which is precisely the point on the create path.
     *
     * <p>Any violation that is not this migration's unique index is rethrown untouched so
     * {@code GlobalExceptionHandler#handleDataIntegrityViolation} keeps its deliberately opaque,
     * anti-enumeration response.
     *
     * @param serviceName echoed to the client so it can name the conflicting entry without a
     *                    second round-trip; {@code null} on the bulk path, where the flush reports
     *                    the constraint rather than which of the N queued rows lost and the
     *                    aborted Postgres transaction rules out looking it up
     */
    private void flushTranslatingDuplicateViolation(@Nullable String serviceName) {
        try {
            serviceRepository.flush();
        } catch (DataIntegrityViolationException ex) {
            if (!isDuplicateServiceViolation(ex)) {
                throw ex;
            }
            // The index reports the constraint, not the surviving row — hence a null id.
            throw new DuplicateServiceException(serviceName, null);
        }
    }

    /**
     * Persists a {@link ServiceDefinition} — new (INSERT) or patched (UPDATE) — translating a
     * violation of the duplicate-service index into the same {@link DuplicateServiceException}
     * the pre-check raises.
     *
     * <p><b>Scope: the paths with nothing to batch the definition write with</b> —
     * {@code addServiceToSalon} (a SALON definition has no assignment yet) and
     * {@code updateServiceDefinition} (a single UPDATE). {@code addIndependentMasterService}
     * deliberately does NOT use this method: it has a second insert to emit, so it saves plainly
     * and calls {@link #flushTranslatingDuplicateViolation} once afterwards, keeping both
     * statements in one flush cycle without weakening the classification below.
     *
     * <p>{@code saveAndFlush} (not {@code save}) is what makes this work: the statement must
     * reach the DB inside this try block, otherwise the violation would surface at commit-time
     * flush — outside any catch — and degrade to the generic
     * {@code DataIntegrityViolationException} 409 with no {@code data.code} for the client to
     * branch on. This closes the TOCTOU window that {@link #assertNoActiveDuplicate} alone
     * leaves open. It matters doubly on the UPDATE path, where {@code save} on an already-managed
     * entity is a no-op that would defer the write to commit.
     *
     * <p>Flush ordering is deliberately unchanged in substance: every subsequent statement on
     * these paths ({@code masterRepository.refreshMinEffectivePrice}, the assignment insert, the
     * affected-master lookup) is JPQL, and Hibernate's AUTO flush mode already flushed the
     * pending write before executing them. {@code saveAndFlush} only makes that flush explicit
     * and earlier. Cache evictions are unaffected — they are all {@code afterCommit} callbacks,
     * not flush-ordered.
     *
     * <p>Any other integrity violation is rethrown untouched so the generic handler keeps its
     * deliberately opaque, anti-enumeration response.
     */
    private ServiceDefinition persistDefinition(ServiceDefinition definition) {
        try {
            return serviceRepository.saveAndFlush(definition);
        } catch (DataIntegrityViolationException ex) {
            if (!isDuplicateServiceViolation(ex)) {
                throw ex;
            }
            // The index reports the constraint, not the surviving row — hence a null id. The
            // client still gets the DUPLICATE_SERVICE code plus the name it just submitted.
            throw new DuplicateServiceException(definition.getName(), null);
        }
    }

    /**
     * True when the violation came from {@code ux_service_def_owner_service_type_active}.
     *
     * <p>Matches ONLY on Hibernate's structured {@code getConstraintName()}. Be precise about what
     * that is: in Hibernate 6 {@code PostgreSQLDialect}'s {@code ViolatedConstraintNameExtractor}
     * calls {@code extractUsingTemplate("violates unique constraint \"", "\"", sqle.getMessage())}
     * — it IS message parsing, just anchored to a fixed server-emitted template rather than a free
     * substring scan. The security conclusion holds regardless: Postgres emits that anchor in the
     * primary message BEFORE the {@code Detail: Failing row contains (…)} line, and
     * {@code extractUsingTemplate} returns the FIRST match, so caller-supplied {@code name} /
     * {@code description} text rendered into the detail cannot steer the extracted name. The
     * value is trustworthy because of where the anchor sits, not because it bypassed the message.
     * {@code ServiceDefinitionUniqueActiveTypeTest} asserts the extraction against real Postgres,
     * since a dialect that returned null or an unqualified name here would silently degrade every
     * genuine duplicate race to a generic 409 with no {@code data.code}.
     *
     * <p><b>Deliberately no message-substring fallback.</b> Scanning {@code cause.getMessage()}
     * for the index name was attacker-steerable: PgJDBC renders {@code Detail: Failing row
     * contains (…)} into the exception message, echoing the caller's own {@code name} /
     * {@code description}. A request carrying
     * {@code description = "ux_service_def_owner_service_type_active"} that tripped a
     * <em>different</em> constraint would then be misclassified as {@code DUPLICATE_SERVICE},
     * turning an opaque 409 into a wrong, caller-chosen error code. A
     * {@code org.postgresql.util.PSQLException#getServerErrorMessage().getConstraint()} fallback
     * would also be structurally safe, but the driver is {@code runtimeOnly} (not on the compile
     * classpath) and the Hibernate extractor already reads exactly that signal.
     *
     * <p>Any non-match returns false so the exception is rethrown unchanged and the generic
     * handler keeps its anti-enumeration response.
     */
    private static boolean isDuplicateServiceViolation(DataIntegrityViolationException ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException hce
                    && DUPLICATE_SERVICE_INDEX.equals(hce.getConstraintName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Validates that the given category name exists in {@code platform_categories}
     * with {@code active = true}.
     *
     * <p>The check is intentionally a plain {@code SELECT EXISTS} via the repository —
     * no JOIN FETCH needed as we only validate presence. The result is not cached here
     * because the write path is infrequent and caching an existence check would require
     * a corresponding eviction on every {@code platform_categories} mutation.
     *
     * @throws BusinessException (400) if the category is unknown or inactive
     */
    private void validateCategoryActive(String category) {
        if (category == null) return;
        // A category is selectable only when it is APPROVED *and* active. PENDING
        // self-service requests and REJECTED rows must never pass this gate.
        if (!platformCategoryRepository.existsByNameAndActiveTrueAndStatus(
                category, com.beautica.service.entity.PlatformCategoryStatus.APPROVED)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Unknown category: " + category);
        }
    }

    /**
     * Resolves and validates an optional {@code serviceTypeId} against a target category.
     *
     * <p>Returns {@code null} when {@code serviceTypeId} is null (the caller leaves the
     * service type untouched). When non-null, the type is loaded, asserted active, and
     * checked for cross-category consistency (Phase 16.3): the type's
     * {@code platform_category_name} must equal {@code targetCategory}.
     *
     * <p>Used by both create (target = request category) and update (target = the effective
     * category after the PATCH — the new category if the request changes it, otherwise the
     * existing one).
     *
     * @param serviceTypeId  optional service-type id (may be {@code null})
     * @param targetCategory the platform-category slug the type must belong to
     * @return the validated {@link ServiceType}, or {@code null} when {@code serviceTypeId} is null
     * @throws BusinessException (400) when the type is inactive or belongs to another category
     */
    @Nullable
    private ServiceType resolveServiceType(@Nullable UUID serviceTypeId, String targetCategory) {
        if (serviceTypeId == null) {
            return null;
        }
        ServiceType type = serviceTypeLookup.getById(serviceTypeId);
        if (!type.isActive()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Service type is not active");
        }
        // Phase 16.3 cross-field guard: a present serviceTypeId must belong to the same
        // platform category the request selected. The parent slug is the plain
        // platform_category_name column re-parented in Phase 16.1 (no FK traversal needed),
        // matched case-sensitively against the target category slug. This requires a DB
        // lookup of the type, so it lives in the service layer rather than bean validation.
        // Null-safe: a null type category yields a clean 400, never a 500 NPE. Post-V73 the
        // platform_category_name column is NOT NULL + FK, so null is unreachable in prod.
        if (!Objects.equals(type.getPlatformCategoryName(), targetCategory)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "service type does not belong to the selected category");
        }
        return type;
    }

    /**
     * Create-path variant of {@link #resolveServiceType(UUID, String)} that mandates a
     * non-null service type. Every service-creation path must persist a
     * {@code service_type_id} (the finer taxonomy the SEARCH filters on); an untyped
     * service is silently dropped by service-type search. The DTO carries {@code @NotNull},
     * but this guard is defense-in-depth so no code path can persist a null type even if
     * validation is bypassed. The returned type is always non-null.
     *
     * @throws BusinessException (400) when {@code serviceTypeId} is null, inactive, or in
     *                           another category
     */
    private ServiceType resolveRequiredServiceType(@Nullable UUID serviceTypeId, String targetCategory) {
        if (serviceTypeId == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Service type is required");
        }
        return resolveServiceType(serviceTypeId, targetCategory);
    }

    /**
     * Resolves the persisted name for a create request.
     *
     * <p>When the request supplies a non-blank name it is used verbatim. When the name is
     * null or blank, the persisted name defaults to the selected service type's Ukrainian
     * display name ({@link ServiceType#getNameUk()}). When neither a name nor a service type
     * is available, the request is rejected with a clear validation error — never persist a
     * blank name.
     *
     * @param requestName the (optional) custom name from the request
     * @param serviceType the resolved service type, or {@code null} when none was selected
     * @return the non-blank name to persist
     * @throws BusinessException (400) when neither a name nor a service type is available
     */
    private String resolveCreateName(@Nullable String requestName, @Nullable ServiceType serviceType) {
        if (requestName != null && !requestName.isBlank()) {
            return requestName;
        }
        if (serviceType == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Name or service type is required");
        }
        return serviceType.getNameUk();
    }
}
