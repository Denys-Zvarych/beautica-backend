package com.beautica.service.service;

import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.master.repository.MasterRepository;
import com.beautica.notification.EmailService;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.service.dto.AssignServiceToMasterRequest;
import com.beautica.service.dto.CatalogCategoryResponse;
import com.beautica.service.dto.CreateServiceDefinitionRequest;
import com.beautica.service.dto.MasterServiceResponse;
import com.beautica.service.dto.ServiceDefinitionResponse;
import com.beautica.service.dto.ServiceTypeResponse;
import com.beautica.service.dto.SuggestServiceTypeRequest;
import com.beautica.service.dto.UpdateServiceDefinitionRequest;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.OwnerType;
import com.beautica.service.entity.ServiceDefinition;
import com.beautica.service.entity.ServiceType;
import com.beautica.service.repository.MasterServiceRepository;
import com.beautica.service.repository.PlatformCategoryRepository;
import com.beautica.service.repository.ServiceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;


import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ServiceCatalogService {

    private final ServiceRepository serviceRepository;
    private final MasterServiceRepository masterServiceRepository;
    private final SalonRepository salonRepository;
    private final MasterRepository masterRepository;
    private final CatalogCategoryLookup catalogCategoryLookup;
    private final PlatformCategoryRepository platformCategoryRepository;
    private final EmailService emailService;
    private final ServiceTypeLookup serviceTypeLookup;
    private final ServiceTypeSearchService serviceTypeSearchService;
    private final CacheManager cacheManager;

    @Value("${app.admin-email}")
    private String adminEmail;

    @Transactional
    public ServiceDefinitionResponse addServiceToSalon(
            UUID salonId,
            CreateServiceDefinitionRequest request) {

        if (!salonRepository.existsById(salonId)) {
            throw new NotFoundException("Salon not found");
        }

        validateCategoryActive(request.category());

        ServiceDefinition definition = ServiceDefinition.builder()
                .ownerType(OwnerType.SALON)
                .ownerId(salonId)
                .name(request.name())
                .description(request.description())
                .category(request.category())
                .baseDurationMinutes(request.baseDurationMinutes())
                .basePrice(request.basePrice())
                .bufferMinutesAfter(request.bufferMinutesAfter())
                .isActive(true)
                .build();

        applyServiceType(definition, request);

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

        ServiceDefinition definition = ServiceDefinition.builder()
                .ownerType(OwnerType.INDEPENDENT_MASTER)
                .ownerId(master.getId())
                .name(request.name())
                .description(request.description())
                .category(request.category())
                .baseDurationMinutes(request.baseDurationMinutes())
                .basePrice(request.basePrice())
                .bufferMinutesAfter(request.bufferMinutesAfter())
                .isActive(true)
                .build();

        applyServiceType(definition, request);

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

    @Transactional(readOnly = true)
    @Cacheable(value = "masterServices", key = "#masterId")
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

    @Transactional
    // Ownership verified by @PreAuthorize("@authz.canManageServiceDefinition") on the controller — any future caller must enforce the same guard.
    public void deactivateServiceDefinition(UUID serviceDefId) {
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
     */
    private void applyPatchFields(ServiceDefinition definition,
            UpdateServiceDefinitionRequest request) {

        if (request.name() != null) {
            definition.setName(request.name());
        }
        if (request.description() != null) {
            definition.setDescription(request.description());
        }
        if (request.category() != null) {
            validateCategoryActive(request.category());
            definition.setCategory(request.category());
        }
        if (request.baseDurationMinutes() != null) {
            definition.setBaseDurationMinutes(request.baseDurationMinutes());
        }
        if (request.basePrice() != null) {
            definition.setBasePrice(request.basePrice());
        }
        if (request.bufferMinutesAfter() != null) {
            definition.setBufferMinutesAfter(request.bufferMinutesAfter());
        }
    }

    @Transactional(readOnly = true)
    public List<CatalogCategoryResponse> getCategories() {
        return catalogCategoryLookup.getAll();
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

    public void suggestServiceType(SuggestServiceTypeRequest request, UUID requestedByUserId) {
        String safeName = sanitizeEmailField(request.name());
        String safeDescription = request.description() != null
                ? sanitizeEmailField(request.description()) : "—";

        String subject = "Beautica: Запит нового типу послуги — " + safeName;
        String body = String.format(
                "Від: %s (userId: %s)%nКатегорія ID: %s%nНазва: %s%nОпис: %s",
                requestedByUserId, requestedByUserId, request.categoryId(),
                safeName, safeDescription
        );
        emailService.sendAdminNotification(adminEmail, subject, body);
    }

    private static String sanitizeEmailField(String value) {
        if (value == null) return "";
        return value.replaceAll("[\r\n\t]", " ").strip();
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

    private void applyServiceType(ServiceDefinition definition, CreateServiceDefinitionRequest request) {
        if (request.serviceTypeId() == null) {
            return;
        }
        ServiceType type = serviceTypeLookup.getById(request.serviceTypeId());
        if (!type.isActive()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Service type is not active");
        }
        definition.setServiceType(type);
    }
}
