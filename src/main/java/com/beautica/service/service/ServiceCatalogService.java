package com.beautica.service.service;

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
import com.beautica.service.dto.MasterServiceResponse;
import com.beautica.service.dto.SalonServiceCatalogResponse;
import com.beautica.service.dto.SalonServiceCategoryGroup;
import com.beautica.service.dto.ServiceDefinitionResponse;
import com.beautica.service.dto.PlatformServiceTypeResponse;
import com.beautica.service.dto.ServiceTypeResponse;
import com.beautica.service.dto.SuggestServiceTypeRequest;
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


import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
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
    private final com.beautica.common.cache.MasterCachePrefixEvictor cachePrefixEvictor;
    private final com.beautica.common.security.AuthorizationService authz;
    private final com.beautica.booking.service.SlotCalculationService slotCalculationService;
    private final SalonCatalogCacheEvictor salonCatalogCacheEvictor;

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

        if (masterServiceRepository.existsByMasterIdAndServiceDefinitionId(masterId, request.serviceDefId())) {
            throw new BusinessException(HttpStatus.CONFLICT, "Service already assigned to this master");
        }

        MasterServiceAssignment assignment = MasterServiceAssignment.builder()
                .master(master)
                .serviceDefinition(serviceDef)
                .priceOverride(request.priceOverride())
                .durationOverrideMinutes(request.durationOverrideMinutes())
                .isActive(true)
                .build();

        MasterServiceAssignment saved = masterServiceRepository.save(assignment);

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
     * {@link #assignServiceToMaster}. Services are owned by the master row, not the salon —
     * no salon-level catalog entity is created.
     *
     * <p>Usable whether or not the master already has services — it backs both the initial
     * catalogue-setup screen and later "add more services" passes.
     *
     * @throws ForbiddenException        if the master does not belong to the given salon
     * @throws DuplicateServiceException (409) if a batch item's service type is already offered
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

        return bulkCreateForMaster(master, OwnerType.INDEPENDENT_MASTER, master.getId(), request);
    }

    /**
     * Shared additive bulk-create core for a resolved master.
     *
     * <p>Services in this platform are owned by the master row regardless of how the master
     * was created (independent or salon-bound), so both entry points persist
     * {@code ownerType = INDEPENDENT_MASTER, ownerId = master.id} and a per-definition
     * {@link MasterServiceAssignment} — identical to {@link #addIndependentMasterService}.
     *
     * <p>Additive by design: the batch is appended to whatever the master already offers, so the
     * same endpoint serves both the empty-catalogue onboarding screen and a later "add more
     * services" pass. The only conflict left is a per-service one — a batch item whose
     * {@link ServiceType} the owner already offers is rejected 409 {@code DUPLICATE_SERVICE}.
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

        // TOCTOU guard: serialize concurrent additive bulk adds for the same master.
        // assertNoActiveDuplicatesInBatch below is a read-then-write check — it reads the
        // owner's already-taken service types, then inserts. Two concurrent bulk POSTs for the
        // same master could therefore both read "type X is free" and both proceed. The V121
        // unique index is the correctness backstop (the loser 500s at flush and is translated to
        // a 409), but taking a transaction-scoped advisory lock keyed by masterId makes the
        // second caller wait for the first to commit, so its duplicate guard sees the committed
        // rows and produces the clean, item-naming 409 instead of a raced constraint violation.
        // Mirrors the booking overlap-guard advisory lock (anti-bug pattern).
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
        acquireBulkSetupLockWithTimeout(master.getId());

        // PERF: and the V121 duplicate guard for the WHOLE batch in ONE query too, rather than
        // one findActiveDuplicateId per item. See assertNoActiveDuplicatesInBatch.
        assertNoActiveDuplicatesInBatch(ownerType, ownerId, request.items(), typesById);

        List<MasterServiceResponse> created = request.items().stream()
                .map(item -> createSingleFromBulkItem(
                        master, ownerType, ownerId, item, typesById.get(item.serviceTypeId())))
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

        return created;
    }

    /**
     * Takes the transaction-scoped per-master advisory lock that serializes the additive
     * bulk-create critical section, bounding the wait at the 3s {@code lock_timeout} the
     * repository query fuses into the same round-trip. The lock lives in its own salt-{@code 2}
     * key space, so it contends only with other bulk setups for the same master — never with the
     * booking master/client locks (salts {@code 0}/{@code 1}); see
     * {@link MasterServiceRepository#acquireBulkSetupLockWithTimeout(UUID)}.
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
     *                           "master is busy, retry" condition, deliberately distinct from the
     *                           semantically loaded 409 this endpoint reserves for
     *                           {@code DUPLICATE_SERVICE}; 500 when the lock query returns no row
     */
    private void acquireBulkSetupLockWithTimeout(UUID masterId) {
        Integer lockResult;
        try {
            lockResult = masterServiceRepository.acquireBulkSetupLockWithTimeout(masterId);
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
                .collect(java.util.stream.Collectors.toMap(ServiceType::getId, java.util.function.Function.identity()));

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
    private void validateBulkCategoriesActive(java.util.Collection<ServiceType> types) {
        Set<String> requested = types.stream()
                .map(ServiceType::getPlatformCategoryName)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));

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
     */
    private MasterServiceResponse createSingleFromBulkItem(
            Master master,
            OwnerType ownerType,
            UUID ownerId,
            BulkServiceItemRequest item,
            ServiceType serviceType) {

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
        // time), so we evict all available-slots cache keys whose first element is a
        // matching masterId using the Caffeine prefix scan pattern (anti-bug §F rule 6).
        evictAvailableSlotsCache(affectedMasterIds);

        // Fix #6 PERF: deactivation removes this definition from every performing master's bookable set,
        // so the shared master-service-bookable free-slot verdict (gating the booking master-list) is now
        // stale for each affected master — evict it by master prefix, alongside masterServices/available-slots.
        evictBookableFutureSlotsCache(affectedMasterIds);

        // Fix #2/#6 PERF: a SALON-owned definition leaving the bookable set changes the salon catalogue.
        // findSalonOwnerId returns the salon id only for a SALON-owned def (empty → master-owned, no
        // catalogue entry). Resolved before the UPDATE (deactivation flips is_active, not ownerType).
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
                                 java.math.BigDecimal price, java.math.BigDecimal priceMin,
                                 java.math.BigDecimal priceMax) {
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
     * Evicts all {@code available-slots} cache entries whose key prefix matches any
     * of the given master IDs.
     *
     * <p>The {@code available-slots} cache key is a SpEL array
     * {@code {masterId, date, masterServiceId}}. Spring renders it as a
     * {@code SimpleKey} whose {@code toString()} starts with {@code "[masterId,"}.
     * Because deactivation has no date/service context, we remove all date × service
     * combinations for the affected masters in one sweep.
     *
     * <p>When a Spring transaction is active, the eviction is deferred to
     * {@code afterCommit} (anti-bug §F rule 2).
     *
     * <p>Fix MEDIUM-9.
     */
    private void evictAvailableSlotsCache(List<UUID> masterIds) {
        if (masterIds.isEmpty()) return;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            doEvictAvailableSlots(masterIds);
                        }
                    }
            );
        } else {
            doEvictAvailableSlots(masterIds);
        }
    }

    private void doEvictAvailableSlots(List<UUID> masterIds) {
        // available-slots is @Cacheable(key = "{#masterId, #date, #masterServiceId}") — an explicit SpEL
        // inline list, so the runtime key is an ArrayList whose first element is the masterId, never a
        // SimpleKey. The local copy of this predicate that used to live here tested `instanceof SimpleKey`
        // and so evicted nothing at all; the shared evictor owns the one correct key-shape check.
        for (UUID masterId : masterIds) {
            cachePrefixEvictor.evictByKeyPrefixNow(masterId, "available-slots");
        }
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
     * prefix — {@link com.beautica.booking.service.SlotCalculationService#evictBookableFutureSlotsByMaster}),
     * alongside {@code masterServices} and {@code available-slots}.
     */
    private void evictBookableFutureSlotsCache(List<UUID> masterIds) {
        if (masterIds.isEmpty()) {
            return;
        }
        Runnable task = () -> masterIds.forEach(slotCalculationService::evictBookableFutureSlotsByMaster);
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
