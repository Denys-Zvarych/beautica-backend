package com.beautica.service.service;

import com.beautica.common.exception.BusinessException;
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
import com.beautica.service.repository.MasterServiceRepository;
import com.beautica.service.repository.PlatformCategoryRepository;
import com.beautica.service.repository.ServiceRepository;
import com.beautica.service.repository.ServiceTypeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
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
@RequiredArgsConstructor
public class ServiceCatalogService {

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
    private final com.beautica.common.security.AuthorizationService authz;

    @Transactional
    public ServiceDefinitionResponse addServiceToSalon(
            UUID salonId,
            CreateServiceDefinitionRequest request) {

        if (!salonRepository.existsById(salonId)) {
            throw new NotFoundException("Salon not found");
        }

        validateCategoryActive(request.category());

        ServiceType serviceType = resolveServiceType(request.serviceTypeId(), request.category());

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
        if (serviceType != null) {
            definition.setServiceType(serviceType);
        }

        ServiceDefinition saved = serviceRepository.save(definition);
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

        ServiceDefinition serviceDef = serviceRepository.findById(request.serviceDefId())
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

        ServiceType serviceType = resolveServiceType(request.serviceTypeId(), request.category());

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
        if (serviceType != null) {
            definition.setServiceType(serviceType);
        }

        ServiceDefinition savedDef = serviceRepository.save(definition);

        MasterServiceAssignment assignment = MasterServiceAssignment.builder()
                .master(master)
                .serviceDefinition(savedDef)
                .isActive(true)
                .build();

        MasterServiceAssignment savedAssignment = masterServiceRepository.save(assignment);

        // PERF-M2: keep the pre-computed min_effective_price in sync for the
        // independent master's own search entry.
        masterRepository.refreshMinEffectivePrice(master.getId());

        // Evict only this master's cache entry after commit — replacing allEntries=true
        // to avoid cold-miss DB round-trips for all other masters (anti-bug §F).
        evictMasterServicesCache(List.of(master.getId()));

        return MasterServiceResponse.from(savedAssignment);
    }

    /**
     * First-time bulk service setup for an INDEPENDENT_MASTER acting on their own behalf.
     *
     * <p>The acting master is resolved from the authenticated principal's {@code userId}
     * (never a client-supplied id), mirroring {@link #addIndependentMasterService}. The
     * batch is created all-or-nothing in this single transaction.
     *
     * @throws ForbiddenException if the user is not an INDEPENDENT_MASTER
     * @throws BusinessException  (409) if the master already has any active service
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
     * First-time bulk service setup performed by a SALON_OWNER/SALON_ADMIN on behalf of a
     * master in their salon (including the owner-operated master row).
     *
     * <p>Salon-membership of the target master is verified here as the second half of the
     * controller's {@code @PreAuthorize} role gate (anti-bug §D split), mirroring
     * {@link #assignServiceToMaster}. Services are owned by the master row, not the salon —
     * no salon-level catalog entity is created.
     *
     * @throws ForbiddenException if the master does not belong to the given salon
     * @throws BusinessException  (409) if the master already has any active service
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
     * Shared first-time bulk-create core for a resolved master.
     *
     * <p>Services in this platform are owned by the master row regardless of how the master
     * was created (independent or salon-bound), so both entry points persist
     * {@code ownerType = INDEPENDENT_MASTER, ownerId = master.id} and a per-definition
     * {@link MasterServiceAssignment} — identical to {@link #addIndependentMasterService}.
     *
     * <p>Enforces the first-time precondition (409 when any active service already exists),
     * rejects duplicate {@code serviceTypeId}s, derives each service name + category from the
     * chosen {@link ServiceType}, reuses {@link #applyPriceMode} for the validated price mode,
     * and persists the whole batch transactionally (all-or-nothing).
     */
    private List<MasterServiceResponse> bulkCreateForMaster(
            Master master,
            OwnerType ownerType,
            UUID ownerId,
            BulkCreateServicesRequest request) {

        // TOCTOU guard: serialize concurrent first-time bulk setups for the same master.
        // existsActiveServiceForMaster is a read-then-write check with no DB-level
        // uniqueness backstop, so two concurrent bulk POSTs could both pass it and both
        // commit, doubling the menu. Acquire a transaction-scoped advisory lock keyed by
        // masterId BEFORE the precondition check (which is re-evaluated under the lock,
        // inside this same @Transactional) — the second caller blocks until the first
        // commits, then its re-check sees the now-existing services and rejects with 409.
        // Mirrors the booking overlap-guard advisory lock (anti-bug pattern).
        Integer lockResult = masterServiceRepository.acquireBulkSetupLock(master.getId());
        if (lockResult == null) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Bulk-setup lock acquisition failed");
        }

        if (masterServiceRepository.existsActiveServiceForMaster(master.getId())) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "Bulk setup is only available for a master with no active services");
        }

        rejectDuplicateServiceTypeIds(request.items());

        // PERF: resolve all service types in ONE query (the ids are already distinct,
        // guaranteed by rejectDuplicateServiceTypeIds) instead of N serialized findById
        // calls, then validate every DISTINCT derived category in ONE query instead of a
        // SELECT EXISTS per item. Both walks share this resolved-types map.
        Map<UUID, ServiceType> typesById = resolveBulkServiceTypes(request.items());
        validateBulkCategoriesActive(typesById.values());

        List<MasterServiceResponse> created = request.items().stream()
                .map(item -> createSingleFromBulkItem(
                        master, ownerType, ownerId, item, typesById.get(item.serviceTypeId())))
                .toList();

        // Keep the pre-computed min_effective_price in sync for the master's search entry
        // (PERF-M2) and evict the master's services cache after commit (anti-bug §F).
        masterRepository.refreshMinEffectivePrice(master.getId());
        evictMasterServicesCache(List.of(master.getId()));

        return created;
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
     * item, using the pre-resolved {@link ServiceType} (type existence, active-check, and
     * category validation are already done in batch by the caller). The service-type's name
     * + parent category are the source of truth for the persisted name and category (no
     * free-text name accepted).
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
     * caller could create two near-identical services in one call, which the first-time
     * setup screen never intends.
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
        return masterServiceRepository
                .findByMasterIdAndIsActiveTrueWithGraph(masterId, PageRequest.of(0, 200))
                .stream()
                .map(MasterServiceResponse::from)
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

        applyPatchFields(definition, request);

        ServiceDefinition saved = serviceRepository.save(definition);

        List<UUID> affectedMasterIds =
                masterServiceRepository.findMasterIdsByServiceDefinitionId(serviceDefId);
        evictMasterServicesCache(affectedMasterIds);

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
     */
    private void applyPatchFields(ServiceDefinition definition,
            UpdateServiceDefinitionRequest request) {

        // Category first: it determines the target category against which a
        // service-type change is consistency-checked below.
        if (request.category() != null) {
            validateCategoryActive(request.category());
            definition.setCategory(request.category());
        }

        // Service type (PATCH): null = leave unchanged (never clears). When present,
        // resolve + active-check + cross-category consistency against the *effective*
        // category — the just-applied new category, or the existing one if unchanged.
        if (request.serviceTypeId() != null) {
            ServiceType serviceType = resolveServiceType(request.serviceTypeId(), definition.getCategory());
            definition.setServiceType(serviceType);
        } else if (request.category() != null && definition.getServiceType() != null) {
            // Category-only PATCH: the existing service type is not re-resolved, so re-check
            // that it still belongs to the just-applied category. Without this guard a
            // category change silently orphans the existing service type (effective pair
            // inconsistent: definition.category != serviceType.platformCategoryName).
            ServiceType existing = definition.getServiceType();
            if (!Objects.equals(existing.getPlatformCategoryName(), definition.getCategory())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST,
                        "service type does not belong to the selected category");
            }
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
     * <p>No caching for v1 (deliberate — keeps this PR scoped): the underlying query is
     * already cheap (indexed {@code owner_type}/{@code owner_id}/{@code is_active} plus an
     * {@code EXISTS} on an indexed FK), unlike {@code getMasterServices} which justified a
     * cache by eliminating a JOIN-heavy graph query on a very hot single-master path.
     *
     * <p>Bounded via an internal soft cap ({@code PageRequest.of(0, 500)}, mirroring
     * {@code getMasterServices}'s {@code PageRequest.of(0, 200)}) rather than exposing a
     * caller-supplied {@code Pageable} — the mobile client needs the WHOLE catalog at once
     * to group it by category, so pagination would only fragment categories across pages.
     */
    @Transactional(readOnly = true)
    public SalonServiceCatalogResponse getSalonServiceCatalog(UUID salonId) {
        List<ServiceDefinition> definitions =
                serviceRepository.findBookableServicesBySalon(salonId, PageRequest.of(0, 500));

        if (definitions.isEmpty()) {
            return new SalonServiceCatalogResponse(List.of());
        }

        Map<String, Integer> categoryOrder = buildCategoryOrder();

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
                        e.getValue().size(),
                        e.getValue().stream().map(ServiceDefinitionResponse::from).toList()))
                .toList();

        return new SalonServiceCatalogResponse(categories);
    }

    /**
     * Builds a {@code category name -> ordinal position} map from the approved+active
     * {@link PlatformCategory} rows, in the same display order the category picker already
     * uses ({@link PlatformCategoryRepository#findApprovedActive}, {@code ORDER BY
     * displayName} — {@link PlatformCategory} carries no explicit {@code sortOrder}
     * column). A {@code ServiceDefinition.category} value with no entry in this map
     * (inactive/legacy/unknown category) sorts alphabetically AFTER every known category
     * in {@link #getSalonServiceCatalog}.
     *
     * <p>Deliberately NOT {@link #getCategories()} / {@link CatalogCategoryLookup}: that
     * returns the separate, orphaned "System A" {@code service_categories} table keyed by
     * {@code nameUk}/{@code nameEn} display strings (e.g. {@code "Nails"}) — those never
     * match {@code ServiceDefinition.category}, which stores the live {@link PlatformCategory
     * #getName()} canonical code (e.g. {@code "MANICURE"}). Using {@code getCategories()}
     * here would silently never match anything, degrading every group to alphabetical order.
     *
     * <p>Delegates the {@code findApprovedActive()} query through {@link
     * PlatformCategoryOrderLookup} (a separate {@code @Cacheable} bean, 60-min TTL) instead of
     * calling {@link #platformCategoryRepository} directly: this data is static,
     * admin-approval-gated reference data identical across every request, so re-querying it on
     * every public {@link #getSalonServiceCatalog} hit was pure waste. A same-class
     * {@code @Cacheable} method would not intercept via the AOP proxy on this self-invocation
     * (same caveat documented on {@link #searchServiceTypes}), hence the separate bean.
     */
    private Map<String, Integer> buildCategoryOrder() {
        List<PlatformCategory> approved = platformCategoryOrderLookup.getApprovedActive();
        Map<String, Integer> order = new LinkedHashMap<>();
        for (int i = 0; i < approved.size(); i++) {
            order.put(approved.get(i).getName(), i);
        }
        return order;
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
        var cache = cacheManager.getCache("available-slots");
        if (cache == null) return;
        Object nativeCache = cache.getNativeCache();
        if (nativeCache instanceof com.github.benmanes.caffeine.cache.Cache<?, ?> caffeineCache) {
            // SimpleKey.toString() renders as "SimpleKey [elem0, elem1, ...]" via Arrays.deepToString.
            // The first element is the masterId UUID string — detect it by substring presence.
            for (UUID masterId : masterIds) {
                String prefix = "[" + masterId + ",";
                caffeineCache.asMap().keySet().removeIf(k ->
                        k instanceof org.springframework.cache.interceptor.SimpleKey
                                && k.toString().contains(prefix));
            }
        } else {
            // Fallback for non-Caffeine caches (e.g., ConcurrentMapCache in tests).
            cache.clear();
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
