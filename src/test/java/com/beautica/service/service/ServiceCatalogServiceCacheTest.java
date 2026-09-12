package com.beautica.service.service;

import com.beautica.config.CacheConfig;
import com.beautica.master.entity.Master;
import com.beautica.master.repository.MasterRepository;
import com.beautica.notification.EmailService;
import com.beautica.salon.entity.Salon;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.service.dto.BulkCreateServicesRequest;
import com.beautica.service.dto.BulkServiceItemRequest;
import com.beautica.service.dto.CreateServiceDefinitionRequest;
import com.beautica.service.dto.MasterServiceResponse;
import com.beautica.service.entity.CatalogCategory;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.OwnerType;
import com.beautica.service.entity.PlatformCategoryStatus;
import com.beautica.service.entity.PriceType;
import com.beautica.service.entity.ServiceDefinition;
import com.beautica.service.entity.ServiceType;
import com.beautica.service.repository.CatalogCategoryRepository;
import com.beautica.service.repository.MasterServiceRepository;
import com.beautica.service.repository.PlatformCategoryRepository;
import com.beautica.service.repository.ServiceRepository;
import com.beautica.service.repository.ServiceTypeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import com.beautica.common.cache.CacheKeyFixtures;

import org.springframework.data.domain.Pageable;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Method;
import java.time.LocalDate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(
        classes = {ServiceCatalogService.class, ServiceTypeLookup.class, ServiceTypeSearchService.class,
                CatalogCategoryLookup.class, PlatformCategoryOrderLookup.class, SalonCatalogCacheEvictor.class,
                CacheConfig.class, com.beautica.common.cache.MasterCachePrefixEvictor.class,
                com.beautica.config.ClockConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@DisplayName("ServiceCatalogService — @Cacheable/@CacheEvict behaviour")
class ServiceCatalogServiceCacheTest {

    @MockBean ServiceRepository serviceRepository;
    @MockBean MasterServiceRepository masterServiceRepository;
    @MockBean SalonRepository salonRepository;
    @MockBean MasterRepository masterRepository;
    @MockBean ServiceTypeRepository serviceTypeRepository;
    @MockBean CatalogCategoryRepository catalogCategoryRepository;
    @MockBean PlatformCategoryRepository platformCategoryRepository;
    @MockBean EmailService emailService;
    @MockBean ServiceTypeSuggestionService serviceTypeSuggestionService;
    // B14: ServiceCatalogService now collaborates with AuthorizationService for the
    // service-layer ownership guard. Mock it (void enforce* defaults to a no-op) so the
    // cache-behaviour tests below exercise the happy owner path.
    @MockBean(name = "authz") com.beautica.common.security.AuthorizationService authz;
    // Phase 23.x: ServiceCatalogService now delegates the catalogue free-slot gate to
    // SlotCalculationService. It is not on the @SpringBootTest classes list, so mock it to satisfy
    // the constructor wiring; the catalogue cache test stubs its filter as a pass-through.
    @MockBean com.beautica.booking.service.SlotCalculationService slotCalculationService;
    // Phase 307 D4 — ServiceCatalogService now collaborates with BookingRepository for the
    // per-assignment future-CONFIRMED-booking unassign guard. Not on the @SpringBootTest classes
    // list, so mock it to satisfy constructor wiring; no test below exercises unassignServiceFromMaster.
    @MockBean com.beautica.booking.repository.BookingRepository bookingRepository;
    // Phase 23.x (perf/security #2): ServiceCatalogService evicts the salon-service-catalog cache via
    // this collaborator on every definition mutation. It is a REAL bean here (on the @SpringBootTest
    // classes list) so its @CacheEvict fires through the AOP proxy — the salon-catalogue eviction tests
    // below assert an actual recompute, not an annotation-count. @Autowired only to make its presence
    // explicit; ServiceCatalogService injects it by constructor.
    @Autowired SalonCatalogCacheEvictor salonCatalogCacheEvictor;

    // Phase 307 MEDIUM-3 (perf audit) — a REAL bean here (on the @SpringBootTest classes list) so
    // the eviction tests below can delegate the mocked SlotCalculationService's
    // evictMasterAvailabilityCaches call into the SAME evictor production uses, and observe an
    // actual Caffeine keyset mutation rather than just a mock invocation count.
    @Autowired com.beautica.common.cache.MasterCachePrefixEvictor cachePrefixEvictor;

    @Autowired ServiceCatalogService serviceCatalogService;
    @Autowired CacheManager cacheManager;

    @BeforeEach
    void clearCache() {
        cacheManager.getCache("masterServices").clear();
        cacheManager.getCache("service-type-by-id").clear();
        cacheManager.getCache("service-types").clear();
        cacheManager.getCache("service-type-search").clear();
        cacheManager.getCache("service-categories").clear();
        cacheManager.getCache("available-slots").clear();
        cacheManager.getCache("platform-category-order").clear();
        cacheManager.getCache("salon-service-catalog").clear();
    }

    // ── sync = true regression pins (Anti-Bug §F-7 — thundering-herd guard) ────
    //
    // sync=true cannot be proven deterministically by a single-threaded times(1) hit;
    // these reflection assertions pin the annotation flag so a future edit that drops
    // sync=true on a hot public/reference read is caught by the build.

    @Test
    @DisplayName("getMasterServices @Cacheable carries sync=true (hot public read, herd guard)")
    void should_declareSyncTrue_on_getMasterServices() throws Exception {
        Method m = ServiceCatalogService.class.getMethod("getMasterServices", UUID.class);
        Cacheable cacheable = m.getAnnotation(Cacheable.class);

        assertThat(cacheable).isNotNull();
        assertThat(cacheable.sync()).isTrue();
    }

    @Test
    @DisplayName("ServiceTypeLookup.getById @Cacheable carries sync=true (single-key reference read)")
    void should_declareSyncTrue_on_serviceTypeLookupGetById() throws Exception {
        Method m = ServiceTypeLookup.class.getDeclaredMethod("getById", UUID.class);
        Cacheable cacheable = m.getAnnotation(Cacheable.class);

        assertThat(cacheable).isNotNull();
        assertThat(cacheable.sync()).isTrue();
    }

    @Test
    @DisplayName("ServiceTypeLookup.getByCategory @Cacheable carries sync=true (hot catalog read)")
    void should_declareSyncTrue_on_serviceTypeLookupGetByCategory() throws Exception {
        Method m = ServiceTypeLookup.class.getDeclaredMethod("getByCategory", UUID.class);
        Cacheable cacheable = m.getAnnotation(Cacheable.class);

        assertThat(cacheable).isNotNull();
        assertThat(cacheable.sync()).isTrue();
    }

    @Test
    @DisplayName("second call to getMasterServices returns cached result without hitting repository")
    void should_notHitRepository_when_getMasterServicesCalledTwice() {
        UUID masterId = UUID.randomUUID();
        when(masterServiceRepository.findByMasterIdAndIsActiveTrueWithGraph(eq(masterId), any(Pageable.class)))
                .thenReturn(List.of());

        serviceCatalogService.getMasterServices(masterId);
        serviceCatalogService.getMasterServices(masterId);

        verify(masterServiceRepository, times(1))
                .findByMasterIdAndIsActiveTrueWithGraph(eq(masterId), any(Pageable.class));
    }

    @Test
    @DisplayName("deactivateServiceDefinition evicts all masterServices cache entries so next getMasterServices re-queries")
    void should_evictCache_when_deactivateServiceDefinitionCalled() {
        UUID masterId = UUID.randomUUID();
        UUID serviceDefId = UUID.randomUUID();

        when(masterServiceRepository.findByMasterIdAndIsActiveTrueWithGraph(eq(masterId), any(Pageable.class)))
                .thenReturn(List.of());
        when(masterServiceRepository.findMasterIdsByServiceDefinitionId(serviceDefId))
                .thenReturn(List.of(masterId));
        when(serviceRepository.deactivateById(serviceDefId)).thenReturn(1);

        // Populate cache
        serviceCatalogService.getMasterServices(masterId);
        // Evict all masterServices entries (owner actor — guard mock is a no-op)
        serviceCatalogService.deactivateServiceDefinition(UUID.randomUUID(), serviceDefId);
        // Cache was evicted — repository must be queried again
        serviceCatalogService.getMasterServices(masterId);

        verify(masterServiceRepository, times(2))
                .findByMasterIdAndIsActiveTrueWithGraph(eq(masterId), any(Pageable.class));
    }

    @Test
    @DisplayName("second addServiceToSalon with same serviceTypeId hits cache — serviceTypeRepository.findById called once")
    void should_hitCache_when_addServiceToSalonCalledTwiceWithSameServiceTypeId() {
        UUID salonId1 = UUID.randomUUID();
        UUID salonId2 = UUID.randomUUID();
        UUID serviceTypeId = UUID.randomUUID();

        ServiceType serviceType = ServiceType.builder()
                .id(serviceTypeId)
                .nameUk("Манікюр")
                .slug("manicure")
                .platformCategoryName("MANICURE")
                .active(true)
                .build();

        ServiceDefinition savedDef1 = ServiceDefinition.builder()
                .id(UUID.randomUUID())
                .ownerType(OwnerType.SALON)
                .ownerId(salonId1)
                .name("Manicure")
                .baseDurationMinutes(60)
                .priceType(PriceType.FIXED)
                .basePrice(new BigDecimal("350.00"))
                .bufferMinutesAfter(10)
                .isActive(true)
                .build();

        ServiceDefinition savedDef2 = ServiceDefinition.builder()
                .id(UUID.randomUUID())
                .ownerType(OwnerType.SALON)
                .ownerId(salonId2)
                .name("Manicure")
                .baseDurationMinutes(60)
                .priceType(PriceType.FIXED)
                .basePrice(new BigDecimal("350.00"))
                .bufferMinutesAfter(10)
                .isActive(true)
                .build();

        CreateServiceDefinitionRequest request = new CreateServiceDefinitionRequest(
                "Manicure", "Classic manicure", "MANICURE", 60, 10,
                PriceType.FIXED, new BigDecimal("350.00"), null, null, serviceTypeId);

        when(serviceTypeRepository.findById(serviceTypeId)).thenReturn(Optional.of(serviceType));
        when(platformCategoryRepository.existsByNameAndActiveTrueAndStatus(
                "MANICURE", PlatformCategoryStatus.APPROVED)).thenReturn(true);
        when(salonRepository.existsById(salonId1)).thenReturn(true);
        when(salonRepository.existsById(salonId2)).thenReturn(true);
        when(serviceRepository.saveAndFlush(any(ServiceDefinition.class)))
                .thenReturn(savedDef1)
                .thenReturn(savedDef2);

        serviceCatalogService.addServiceToSalon(salonId1, request);
        serviceCatalogService.addServiceToSalon(salonId2, request);

        verify(serviceTypeRepository, times(1)).findById(serviceTypeId);
    }

    @Test
    @DisplayName("second searchServiceTypes(null, null) hits cache — findAllActiveWithCategory called once")
    void should_hitCache_when_searchServiceTypesCalledTwiceWithNullCategory() {
        when(serviceTypeRepository.findAllActiveWithCategory()).thenReturn(List.of());

        serviceCatalogService.searchServiceTypes(null, null);
        serviceCatalogService.searchServiceTypes(null, null);

        verify(serviceTypeRepository, times(1)).findAllActiveWithCategory();
    }

    @Test
    @DisplayName("second searchServiceTypes(categoryId, null) hits cache — category-scoped repo method called once")
    void should_hitCache_when_searchServiceTypesCalledTwiceWithSameCategoryId() {
        UUID categoryId = UUID.randomUUID();
        CatalogCategory category = CatalogCategory.builder()
                .id(categoryId)
                .nameUk("Test")
                .nameEn("Test")
                .sortOrder(1)
                .build();
        when(catalogCategoryRepository.findAllByOrderBySortOrderAsc()).thenReturn(List.of(category));
        when(serviceTypeRepository.findByCategoryWithCategory(categoryId)).thenReturn(List.of());

        serviceCatalogService.searchServiceTypes(categoryId, null);
        serviceCatalogService.searchServiceTypes(categoryId, null);

        verify(serviceTypeRepository, times(1)).findByCategoryWithCategory(categoryId);
        verify(catalogCategoryRepository, times(1)).findAllByOrderBySortOrderAsc();
    }

    @Test
    @DisplayName("second call to searchServiceTypes with same q does not re-query repository — cache is effective")
    void should_hitCache_when_searchServiceTypesCalledTwice() {
        when(serviceTypeRepository.searchByName(eq("ман"), any())).thenReturn(List.of());

        serviceCatalogService.searchServiceTypes(null, "Ман");
        serviceCatalogService.searchServiceTypes(null, "Ман");

        verify(serviceTypeRepository, times(1)).searchByName(eq("ман"), any());
    }

    @Test
    @DisplayName("(q, categoryId) and (q, null) are cached independently — repo called once per distinct key")
    void should_cacheIndependently_when_queryWithAndWithoutCategoryId() {
        UUID categoryId = UUID.randomUUID();
        CatalogCategory category = CatalogCategory.builder()
                .id(categoryId)
                .nameUk("Test")
                .nameEn("Test")
                .sortOrder(1)
                .build();
        when(catalogCategoryRepository.findAllByOrderBySortOrderAsc()).thenReturn(List.of(category));
        when(serviceTypeRepository.searchByName(eq("ман"), any())).thenReturn(List.of());
        when(serviceTypeRepository.searchByNameAndCategory(eq("ман"), eq(categoryId), any())).thenReturn(List.of());

        serviceCatalogService.searchServiceTypes(null, "Ман");     // cache key "ман:null"
        serviceCatalogService.searchServiceTypes(categoryId, "Ман"); // cache key "ман:<categoryId>"
        serviceCatalogService.searchServiceTypes(null, "Ман");     // cache hit for "ман:null"
        serviceCatalogService.searchServiceTypes(categoryId, "Ман"); // cache hit for "ман:<categoryId>"

        verify(serviceTypeRepository, times(1)).searchByName(eq("ман"), any());
        verify(serviceTypeRepository, times(1)).searchByNameAndCategory(eq("ман"), eq(categoryId), any());
    }

    @Test
    @DisplayName("second call to getCategories does not hit repository — service-categories cache is effective")
    void should_hitCache_when_getCategoriesCalledTwice() {
        when(catalogCategoryRepository.findAllByOrderBySortOrderAsc()).thenReturn(List.of());

        serviceCatalogService.getCategories();
        serviceCatalogService.getCategories();

        verify(catalogCategoryRepository, times(1)).findAllByOrderBySortOrderAsc();
    }

    /**
     * Phase 307 MEDIUM-3 (perf audit) removed {@code deactivateServiceDefinition}'s and
     * {@code unassignServiceFromMaster}'s direct, synchronous {@code evictAvailableSlotsCache}
     * call — it scanned the SAME {@code available-slots} cache, for the SAME masters, that
     * {@code evictBookableFutureSlotsCache}'s off-thread sweep
     * ({@code SlotCalculationService#evictMasterAvailabilityCaches}, keyed off
     * {@code BOOKING_WRITE_CACHES}) already covers as a superset.
     *
     * <p>{@code slotCalculationService} is a {@code @MockBean} in this slice, so that off-thread
     * sweep is normally a no-op stub here. This helper delegates the mock's
     * {@code evictMasterAvailabilityCaches} call into the REAL {@link #cachePrefixEvictor} bean
     * (on the {@code @SpringBootTest} classes list), covering the same three cache names
     * {@code BOOKING_WRITE_CACHES} does, so the eviction tests below observe an actual Caffeine
     * keyset mutation — proof the sweep still owns the eviction after MEDIUM-3 — rather than
     * merely a mock invocation count.
     */
    private void delegateSlotEvictionToRealCache() {
        org.mockito.Mockito.doAnswer(invocation -> {
            UUID evictedMasterId = invocation.getArgument(0);
            cachePrefixEvictor.evictByKeyPrefixNow(evictedMasterId,
                    "available-slots", "master-service-bookable", "master-bookable-days");
            return null;
        }).when(slotCalculationService).evictMasterAvailabilityCaches(any());
    }

    @Test
    @DisplayName("deactivateServiceDefinition evicts available-slots cache entries for affected masters")
    void should_evictAvailableSlotsCache_when_deactivateServiceDefinitionCalled() {
        // Arrange — pre-populate an available-slots entry keyed the way SlotCalculationService's
        // @Cacheable(key = "{#masterId, #date, #masterServiceId}") really keys it: an explicit SpEL
        // inline list evaluates to a List, and is NEVER wrapped in a SimpleKey (that type comes only
        // from the default SimpleKeyGenerator, used when no `key` attribute is given).
        //
        // SlotCalculationService is a @MockBean in this slice, so its real @Cacheable proxy cannot be
        // driven from here. The key therefore comes from CacheKeyFixtures.spelKey rather than being
        // hand-rolled — and that helper is not an assumption: CachePrefixEvictionKeyShapeTest drives
        // the real getAvailableSlots proxy and asserts Spring's actual key equals its output. This
        // test covers the write-path wiring (does deactivation evict the affected masters at all);
        // the shape is proven against ground truth there.
        //
        // The previous version seeded `new SimpleKey(...)`, which matched the equally-wrong
        // production predicate — so it passed while the real eviction removed nothing.
        //
        // Phase 307 MEDIUM-3 — the direct synchronous call this test used to exercise is gone;
        // delegateSlotEvictionToRealCache wires the off-thread sweep (now the sole evictor) to the
        // real cache so this test still observes a real eviction, not just a mock call.
        UUID masterId = UUID.randomUUID();
        UUID serviceDefId = UUID.randomUUID();
        UUID someServiceId = UUID.randomUUID();
        LocalDate someDate = LocalDate.of(2026, 6, 1);

        var slotsCache = cacheManager.getCache("available-slots");
        var cacheKey = CacheKeyFixtures.spelKey(masterId, someDate, someServiceId);
        slotsCache.put(cacheKey, List.of("09:00", "10:00"));

        delegateSlotEvictionToRealCache();
        when(masterServiceRepository.findMasterIdsByServiceDefinitionId(serviceDefId))
                .thenReturn(List.of(masterId));
        when(serviceRepository.deactivateById(serviceDefId)).thenReturn(1);

        // Act — no active Spring transaction here; eviction runs immediately in the else-branch.
        serviceCatalogService.deactivateServiceDefinition(UUID.randomUUID(), serviceDefId);

        // Assert — the cache entry for the affected master must be gone.
        assertThat(slotsCache.get(cacheKey)).isNull();
    }

    /**
     * Phase 307 audit LOW-4 — closes the gap MEDIUM-3 opened: nothing previously asserted that
     * {@code available-slots} is evicted on {@code unassignServiceFromMaster} at all (case 16 in
     * {@code MasterServiceUnassignIT} only proves the salon catalogue). Asserts eviction happens
     * — NOT that it happens synchronously, since after MEDIUM-3 it is the off-thread sweep behind
     * {@code evictBookableFutureSlotsCache} that owns it, not a direct call this method makes
     * itself.
     */
    @Test
    @DisplayName("unassignServiceFromMaster eventually evicts available-slots for the master, via "
            + "the off-thread sweep (Phase 307 audit LOW-4)")
    void should_evictAvailableSlotsCache_when_unassignServiceFromMasterCalled() {
        UUID salonId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID serviceDefId = UUID.randomUUID();
        UUID someServiceId = UUID.randomUUID();
        LocalDate someDate = LocalDate.of(2026, 6, 1);

        var slotsCache = cacheManager.getCache("available-slots");
        var cacheKey = CacheKeyFixtures.spelKey(masterId, someDate, someServiceId);
        slotsCache.put(cacheKey, List.of("09:00", "10:00"));

        delegateSlotEvictionToRealCache();

        Salon salon = Salon.builder().id(salonId).build();
        Master master = Master.builder().id(masterId).salon(salon).isActive(true).build();
        ServiceDefinition serviceDefinition = ServiceDefinition.builder()
                .id(serviceDefId)
                .ownerType(OwnerType.SALON)
                .ownerId(salonId)
                .isActive(true)
                .build();
        MasterServiceAssignment assignment = MasterServiceAssignment.builder()
                .id(UUID.randomUUID())
                .master(master)
                .serviceDefinition(serviceDefinition)
                .isActive(true)
                .build();

        when(masterServiceRepository.findByMasterIdAndServiceDefinitionId(masterId, serviceDefId))
                .thenReturn(Optional.of(assignment));
        when(bookingRepository.countConfirmedFutureByMasterServiceId(
                eq(masterId), eq(assignment.getId()), any())).thenReturn(0L);

        // Act — no active Spring transaction here; the (stubbed, delegating) off-thread sweep runs
        // immediately in unassignServiceFromMaster's else-branch.
        serviceCatalogService.unassignServiceFromMaster(salonId, masterId, serviceDefId);

        // Assert — eventually evicted (behaviourally, via CacheManager), not synchronously: after
        // MEDIUM-3 the off-thread sweep is the ONLY path that evicts available-slots here.
        assertThat(slotsCache.get(cacheKey))
                .as("available-slots must still be evicted for the master after MEDIUM-3 removed "
                        + "the redundant direct call")
                .isNull();
    }

    // ── Phase 311 D11 — updateMasterServiceBand's CONDITIONAL eviction ─────────

    /**
     * Builds a resolvable {@code (salonId, masterId, serviceDefId)} triple for
     * {@code updateMasterServiceBand}: a SALON-owned, active {@link ServiceDefinition}, an active
     * {@link Master} belonging to {@code salonId}, and a {@link MasterServiceAssignment} carrying
     * the given PRE-EDIT override state. {@code masterServiceRepository
     * .findByMasterIdAndServiceDefinitionId} is stubbed to return it.
     */
    private MasterServiceAssignment stubBandAssignment(
            UUID salonId, UUID masterId, UUID serviceDefId,
            BigDecimal existingPriceOverride, Integer existingDurationOverride) {
        Salon salon = Salon.builder().id(salonId).build();
        Master master = Master.builder().id(masterId).salon(salon).isActive(true).build();
        ServiceDefinition serviceDefinition = ServiceDefinition.builder()
                .id(serviceDefId)
                .ownerType(OwnerType.SALON)
                .ownerId(salonId)
                .priceType(PriceType.FIXED)
                .basePrice(new BigDecimal("400.00"))
                .isActive(true)
                .build();
        MasterServiceAssignment assignment = MasterServiceAssignment.builder()
                .id(UUID.randomUUID())
                .master(master)
                .serviceDefinition(serviceDefinition)
                .priceOverride(existingPriceOverride)
                .priceTypeOverride(existingPriceOverride != null ? PriceType.FIXED : null)
                .durationOverrideMinutes(existingDurationOverride)
                .isActive(true)
                .build();
        when(masterServiceRepository.findByMasterIdAndServiceDefinitionId(masterId, serviceDefId))
                .thenReturn(Optional.of(assignment));
        return assignment;
    }

    @Test
    @DisplayName("Phase 311 D11: updateMasterServiceBand ALWAYS evicts masterServices and "
            + "salon-service-catalog, for a band-only edit")
    void should_evictMasterServicesAndSalonCatalog_when_updateMasterServiceBandCalled() {
        UUID salonId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID serviceDefId = UUID.randomUUID();
        stubBandAssignment(salonId, masterId, serviceDefId, null, null);

        var masterServicesCache = cacheManager.getCache("masterServices");
        masterServicesCache.put(masterId, List.of());
        var salonCatalogCache = cacheManager.getCache("salon-service-catalog");
        salonCatalogCache.put(salonId, "stale-catalog-snapshot");

        var request = new com.beautica.service.dto.UpdateMasterServiceBandRequest(
                PriceType.FIXED, new BigDecimal("750.00"), null, null, null, null);

        // Act — no active Spring transaction here; eviction runs immediately in the else-branch.
        serviceCatalogService.updateMasterServiceBand(UUID.randomUUID(), salonId, masterId, serviceDefId, request);

        assertThat(masterServicesCache.get(masterId)).isNull();
        assertThat(salonCatalogCache.get(salonId)).isNull();
    }

    @Test
    @DisplayName("Phase 311 D11 / case 24 / mutation 12: a DURATION edit evicts available-slots for "
            + "the master")
    void should_evictAvailableSlotsCache_when_updateMasterServiceBandChangesDuration() {
        UUID salonId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID serviceDefId = UUID.randomUUID();
        UUID someServiceId = UUID.randomUUID();
        LocalDate someDate = LocalDate.of(2026, 6, 1);
        stubBandAssignment(salonId, masterId, serviceDefId, null, null);

        var slotsCache = cacheManager.getCache("available-slots");
        var cacheKey = CacheKeyFixtures.spelKey(masterId, someDate, someServiceId);
        slotsCache.put(cacheKey, List.of("09:00", "10:00"));
        delegateSlotEvictionToRealCache();

        var request = new com.beautica.service.dto.UpdateMasterServiceBandRequest(
                null, null, null, 90, null, null);

        serviceCatalogService.updateMasterServiceBand(UUID.randomUUID(), salonId, masterId, serviceDefId, request);

        assertThat(slotsCache.get(cacheKey))
                .as("duration_override_minutes changed (null -> 90) — slot caches MUST sweep")
                .isNull();
    }

    @Test
    @DisplayName("Phase 311 D11 / case 24 / mutation 12: a BAND-ONLY edit does NOT evict "
            + "available-slots — the conditional's whole point")
    void should_notEvictAvailableSlotsCache_when_updateMasterServiceBandOnlyChangesBand() {
        UUID salonId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID serviceDefId = UUID.randomUUID();
        UUID someServiceId = UUID.randomUUID();
        LocalDate someDate = LocalDate.of(2026, 6, 1);
        stubBandAssignment(salonId, masterId, serviceDefId, null, null);

        var slotsCache = cacheManager.getCache("available-slots");
        var cacheKey = CacheKeyFixtures.spelKey(masterId, someDate, someServiceId);
        slotsCache.put(cacheKey, List.of("09:00", "10:00"));
        delegateSlotEvictionToRealCache();

        // Band-only: no durationOverrideMinutes, no clearDurationOverride.
        var request = new com.beautica.service.dto.UpdateMasterServiceBandRequest(
                PriceType.FIXED, new BigDecimal("750.00"), null, null, null, null);

        serviceCatalogService.updateMasterServiceBand(UUID.randomUUID(), salonId, masterId, serviceDefId, request);

        assertThat(slotsCache.get(cacheKey))
                .as("a band-only edit must NOT sweep slot caches — duration is unchanged")
                .isNotNull();
    }

    @Test
    @DisplayName("Phase 311 D11 / case 25 / mutation 13: a floor change refreshes "
            + "masters.min_effective_price")
    void should_refreshMinEffectivePrice_when_updateMasterServiceBandChangesFloor() {
        UUID salonId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID serviceDefId = UUID.randomUUID();
        stubBandAssignment(salonId, masterId, serviceDefId, new BigDecimal("500.00"), null);

        var request = new com.beautica.service.dto.UpdateMasterServiceBandRequest(
                PriceType.FIXED, new BigDecimal("300.00"), null, null, null, null);

        serviceCatalogService.updateMasterServiceBand(UUID.randomUUID(), salonId, masterId, serviceDefId, request);

        verify(masterRepository, times(1)).refreshMinEffectivePrice(masterId);
    }

    @Test
    @DisplayName("Phase 311 D11: a duration-only edit does NOT refresh min_effective_price — the "
            + "floor did not change")
    void should_notRefreshMinEffectivePrice_when_updateMasterServiceBandOnlyChangesDuration() {
        UUID salonId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID serviceDefId = UUID.randomUUID();
        stubBandAssignment(salonId, masterId, serviceDefId, null, null);

        var request = new com.beautica.service.dto.UpdateMasterServiceBandRequest(
                null, null, null, 90, null, null);

        serviceCatalogService.updateMasterServiceBand(UUID.randomUUID(), salonId, masterId, serviceDefId, request);

        verify(masterRepository, org.mockito.Mockito.never()).refreshMinEffectivePrice(any());
    }

    // ── platform-category-order cache (perf follow-up, Phase 13.6) ─────────────

    @Test
    @DisplayName("second getSalonServiceCatalog call for a different salon does not re-query findApprovedActive — platform-category-order cache is effective")
    void should_hitCache_when_getSalonServiceCatalogCalledForTwoSalons() {
        UUID salonId1 = UUID.randomUUID();
        UUID salonId2 = UUID.randomUUID();

        ServiceDefinition manicure = ServiceDefinition.builder()
                .id(UUID.randomUUID())
                .ownerType(OwnerType.SALON)
                .name("Manicure")
                .category("MANICURE")
                .baseDurationMinutes(60)
                .bufferMinutesAfter(0)
                .isActive(true)
                .priceType(PriceType.FIXED)
                .basePrice(new BigDecimal("300.00"))
                .build();

        // Phase 23.x: catalogue loads candidate ASSIGNMENTS then runs the free-slot gate per master.
        com.beautica.master.entity.Master master1 =
                com.beautica.master.entity.Master.builder().id(UUID.randomUUID()).isActive(true).build();
        com.beautica.master.entity.Master master2 =
                com.beautica.master.entity.Master.builder().id(UUID.randomUUID()).isActive(true).build();
        com.beautica.service.entity.MasterServiceAssignment a1 =
                com.beautica.service.entity.MasterServiceAssignment.builder()
                        .id(UUID.randomUUID()).master(master1).serviceDefinition(manicure).isActive(true).build();
        com.beautica.service.entity.MasterServiceAssignment a2 =
                com.beautica.service.entity.MasterServiceAssignment.builder()
                        .id(UUID.randomUUID()).master(master2).serviceDefinition(manicure).isActive(true).build();
        when(masterServiceRepository.findBookableAssignmentsBySalon(salonId1)).thenReturn(List.of(a1));
        when(masterServiceRepository.findBookableAssignmentsBySalon(salonId2)).thenReturn(List.of(a2));
        when(slotCalculationService.filterBookableAssignments(any(), any()))
                .thenAnswer(inv -> inv.getArgument(1));
        when(platformCategoryRepository.findApprovedActive())
                .thenReturn(List.of(com.beautica.service.entity.PlatformCategory.ofApproved("MANICURE", "Манікюр")));

        serviceCatalogService.getSalonServiceCatalog(salonId1);
        serviceCatalogService.getSalonServiceCatalog(salonId2);

        // Two distinct salons still share the SAME platform-category-order cache entry
        // ('all' key, no salonId in the key) — the repository must be hit only once.
        verify(platformCategoryRepository, times(1)).findApprovedActive();
    }

    // ── salon-service-catalog cache (perf/security #2) ─────────────────────────
    //
    // getSalonServiceCatalog is @Cacheable("salon-service-catalog", key=#salonId, sync=true). A repeat
    // call within TTL must be served from cache; a service-def mutation for the salon must evict THAT
    // salon's entry (only) via the real SalonCatalogCacheEvictor.@CacheEvict so the next call recomputes.

    @Test
    @DisplayName("second getSalonServiceCatalog for the same salon within TTL is served from cache — findBookableAssignmentsBySalon called once")
    void should_serveSalonCatalogFromCache_when_calledTwiceForSameSalon() {
        UUID salonId = UUID.randomUUID();
        when(masterServiceRepository.findBookableAssignmentsBySalon(salonId)).thenReturn(List.of());

        serviceCatalogService.getSalonServiceCatalog(salonId);
        serviceCatalogService.getSalonServiceCatalog(salonId);

        verify(masterServiceRepository, times(1)).findBookableAssignmentsBySalon(salonId);
    }

    @Test
    @DisplayName("deactivateServiceDefinition evicts ONLY the owning salon's catalogue entry — that salon recomputes, a sibling salon stays cached (correct salonId source via findSalonOwnerId)")
    void should_evictOnlyOwningSalonCatalog_when_serviceDefinitionDeactivated() {
        UUID salonA = UUID.randomUUID();
        UUID salonB = UUID.randomUUID();
        UUID serviceDefId = UUID.randomUUID();

        when(masterServiceRepository.findBookableAssignmentsBySalon(salonA)).thenReturn(List.of());
        when(masterServiceRepository.findBookableAssignmentsBySalon(salonB)).thenReturn(List.of());
        // Deactivation resolves the salon id from the def's SALON owner — this is the salonId the
        // eviction must target. No performing masters keeps the other eviction paths no-ops.
        when(masterServiceRepository.findMasterIdsByServiceDefinitionId(serviceDefId)).thenReturn(List.of());
        when(serviceRepository.findSalonOwnerId(serviceDefId)).thenReturn(Optional.of(salonA));
        when(serviceRepository.deactivateById(serviceDefId)).thenReturn(1);

        // Populate both salons' catalogue entries.
        serviceCatalogService.getSalonServiceCatalog(salonA);
        serviceCatalogService.getSalonServiceCatalog(salonB);

        // Mutate salon A's catalogue — the real evictor fires @CacheEvict(key = salonA) after (no active
        // transaction here, so evictSalonCatalogAfterCommit runs the eviction synchronously).
        serviceCatalogService.deactivateServiceDefinition(UUID.randomUUID(), serviceDefId);

        // Salon A recomputes (cache evicted); salon B stays cached (per-key eviction, not a blanket clear).
        serviceCatalogService.getSalonServiceCatalog(salonA);
        serviceCatalogService.getSalonServiceCatalog(salonB);

        verify(masterServiceRepository, times(2)).findBookableAssignmentsBySalon(salonA);
        verify(masterServiceRepository, times(1)).findBookableAssignmentsBySalon(salonB);
    }

    // ── masterServices cache SHAPE (QA gap G6, Q7) ──────────────────────────────
    //
    // getMasterServices' javadoc makes a load-bearing safety claim: masking (fromPublic,
    // dropping priceOverride) happens INSIDE the @Cacheable method, so the "masterServices"
    // cache entry itself is already masked — not just the value handed back to this call's
    // caller. Prior tests above only pin call counts (cache hit/miss), which would stay green
    // even if masking moved OUT of the cached method (e.g. into the controller) and the cache
    // started holding the unmasked shape — a priceOverride leak to anonymous callers. These two
    // tests read the actual stored cache entry via the injected real CacheManager (this class's
    // Spring context has CacheConfig on the @SpringBootTest classes list, so @Cacheable's AOP
    // proxy is genuinely active here — this is NOT a plain-Mockito unit test) to pin the shape
    // claim behaviourally.

    @Test
    @DisplayName("public getMasterServices call caches the already-masked shape (priceOverride null in the stored entry, effectivePrice still derived from the override)")
    void should_cacheTheAlreadyMaskedShape_when_publicPathPopulatesMasterServices() {
        UUID masterId = UUID.randomUUID();
        BigDecimal override = new BigDecimal("123.45");

        ServiceDefinition definition = ServiceDefinition.builder()
                .id(UUID.randomUUID())
                .ownerType(OwnerType.SALON)
                .name("Manicure")
                .category("MANICURE")
                .baseDurationMinutes(60)
                .bufferMinutesAfter(0)
                .isActive(true)
                .priceType(PriceType.FIXED)
                .basePrice(new BigDecimal("300.00"))
                .build();
        Master master = Master.builder().id(masterId).isActive(true).build();
        MasterServiceAssignment assignment = MasterServiceAssignment.builder()
                .id(UUID.randomUUID())
                .master(master)
                .serviceDefinition(definition)
                .priceOverride(override)
                .isActive(true)
                .build();

        when(masterServiceRepository.findByMasterIdAndIsActiveTrueWithGraph(eq(masterId), any(Pageable.class)))
                .thenReturn(List.of(assignment));

        List<MasterServiceResponse> returned = serviceCatalogService.getMasterServices(masterId);

        // The returned value is masked, and the mask is a projection: effectivePrice still
        // reflects the override that priceOverride itself no longer discloses.
        assertThat(returned).hasSize(1);
        assertThat(returned.get(0).priceOverride()).isNull();
        assertThat(returned.get(0).effectivePrice()).isEqualByComparingTo(override);

        // The whole point of this test: assert the STORED cache value, not just the return
        // value — this is what distinguishes "masked on the way out" from "masked before storing".
        List<?> cachedRaw = cacheManager.getCache("masterServices").get(masterId, List.class);
        assertThat(cachedRaw).isNotNull().hasSize(1);
        MasterServiceResponse cachedEntry = (MasterServiceResponse) cachedRaw.get(0);
        assertThat(cachedEntry.priceOverride()).isNull();
        assertThat(cachedEntry.effectivePrice()).isEqualByComparingTo(override);
    }

    @Test
    @DisplayName("provider's own getMyServices call after the public path has cached the master does not pick up the masked entry — priceOverride comes back unmasked")
    void should_returnUnmaskedPriceOverride_when_providerReadsOwnServicesAfterPublicPathCached() {
        UUID userId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        BigDecimal override = new BigDecimal("77.00");

        ServiceDefinition definition = ServiceDefinition.builder()
                .id(UUID.randomUUID())
                .ownerType(OwnerType.SALON)
                .name("Manicure")
                .category("MANICURE")
                .baseDurationMinutes(60)
                .bufferMinutesAfter(0)
                .isActive(true)
                .priceType(PriceType.FIXED)
                .basePrice(new BigDecimal("300.00"))
                .build();
        Master master = Master.builder().id(masterId).isActive(true).build();
        MasterServiceAssignment assignment = MasterServiceAssignment.builder()
                .id(UUID.randomUUID())
                .master(master)
                .serviceDefinition(definition)
                .priceOverride(override)
                .isActive(true)
                .build();

        when(masterServiceRepository.findByMasterIdAndIsActiveTrueWithGraph(eq(masterId), any(Pageable.class)))
                .thenReturn(List.of(assignment));
        when(masterRepository.findByUserId(userId)).thenReturn(Optional.of(master));

        // Populate the "masterServices" cache with the masked shape via the public path first.
        serviceCatalogService.getMasterServices(masterId);

        // getMyServices is uncached (no @Cacheable) and must not read the masked cache entry —
        // it re-queries the repository directly and returns the full, unmasked variant.
        List<MasterServiceResponse> own = serviceCatalogService.getMyServices(userId);

        assertThat(own).hasSize(1);
        assertThat(own.get(0).priceOverride()).isEqualByComparingTo(override);
    }

    // ── Phase 304 — bulkCreateSalonMasterServices salon-catalogue eviction (cases 5-8) ─────────

    private BulkCreateServicesRequest oneItemBulkRequest(UUID serviceTypeId) {
        return new BulkCreateServicesRequest(List.of(
                new BulkServiceItemRequest(
                        serviceTypeId, 45, PriceType.FIXED, new BigDecimal("250.00"), null, null)));
    }

    /** Wires the mocks a happy-path {@code bulkCreateSalonMasterServices} call needs to succeed. */
    private void stubBulkCreateHappyPath(UUID salonId, UUID masterId, UUID serviceTypeId) {
        Salon salon = Salon.builder().id(salonId).build();
        Master master = Master.builder().id(masterId).salon(salon).build();
        ServiceType serviceType = ServiceType.builder()
                .id(serviceTypeId)
                .nameUk("Стрижка")
                .nameEn("Haircut")
                .slug("strizhka-304-" + serviceTypeId)
                .platformCategoryName("HAIR")
                .active(true)
                .build();

        when(masterRepository.findById(masterId)).thenReturn(Optional.of(master));
        when(serviceTypeRepository.findAllById(any())).thenReturn(List.of(serviceType));
        when(platformCategoryRepository.findSelectableNamesIn(any())).thenReturn(List.of("HAIR"));
        when(serviceRepository.save(any(ServiceDefinition.class))).thenAnswer(inv -> {
            ServiceDefinition def = inv.getArgument(0);
            def.setId(UUID.randomUUID());
            return def;
        });
        when(masterServiceRepository.save(any(MasterServiceAssignment.class))).thenAnswer(inv -> {
            MasterServiceAssignment msa = inv.getArgument(0);
            msa.setId(UUID.randomUUID());
            return msa;
        });
    }

    @Test
    @DisplayName("Phase 304 case 5: bulkCreateSalonMasterServices registers exactly one "
            + "salon-catalogue eviction, for the target salon only — a sibling salon's cached "
            + "entry survives untouched (D1)")
    void should_evictOnlyTargetSalonCatalog_when_bulkCreateSalonMasterServicesCalled() {
        UUID salonA = UUID.randomUUID();
        UUID salonB = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID serviceTypeId = UUID.randomUUID();
        stubBulkCreateHappyPath(salonA, masterId, serviceTypeId);
        when(masterServiceRepository.findBookableAssignmentsBySalon(salonA)).thenReturn(List.of());
        when(masterServiceRepository.findBookableAssignmentsBySalon(salonB)).thenReturn(List.of());

        // Warm both salons' catalogue entries.
        serviceCatalogService.getSalonServiceCatalog(salonA);
        serviceCatalogService.getSalonServiceCatalog(salonB);

        // Act — no active Spring transaction here; the eviction runs immediately (else-branch).
        serviceCatalogService.bulkCreateSalonMasterServices(salonA, masterId, oneItemBulkRequest(serviceTypeId));

        // Salon A recomputes (evicted); salon B stays cached (per-key, not allEntries).
        serviceCatalogService.getSalonServiceCatalog(salonA);
        serviceCatalogService.getSalonServiceCatalog(salonB);

        verify(masterServiceRepository, times(2)).findBookableAssignmentsBySalon(salonA);
        verify(masterServiceRepository, times(1)).findBookableAssignmentsBySalon(salonB);
    }

    @Test
    @DisplayName("Phase 304 case 6: the salon-catalogue eviction from bulkCreateSalonMasterServices "
            + "is registered as an afterCommit synchronization, never executed inline (D2)")
    void should_deferSalonCatalogEvictionToAfterCommit_when_bulkCreateSalonMasterServicesCalled() {
        UUID salonId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID serviceTypeId = UUID.randomUUID();
        stubBulkCreateHappyPath(salonId, masterId, serviceTypeId);
        when(masterServiceRepository.findBookableAssignmentsBySalon(salonId)).thenReturn(List.of());

        // Warm the catalogue cache.
        serviceCatalogService.getSalonServiceCatalog(salonId);

        TransactionSynchronizationManager.initSynchronization();
        try {
            serviceCatalogService.bulkCreateSalonMasterServices(
                    salonId, masterId, oneItemBulkRequest(serviceTypeId));

            // MUTATION-RED for "move the eviction inline": an inline eviction would already have
            // cleared this entry here, before commit — a parallel reader would then repopulate the
            // cache from this pre-commit snapshot (anti-bug §F rule 2).
            assertThat(cacheManager.getCache("salon-service-catalog").get(salonId))
                    .as("commit has not happened yet (the synchronization is still active) — the "
                            + "pre-write cache entry must still be present")
                    .isNotNull();

            List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
            assertThat(syncs).as("an afterCommit callback must have been registered").isNotEmpty();
            syncs.forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        assertThat(cacheManager.getCache("salon-service-catalog").get(salonId))
                .as("after the simulated commit, the entry must be evicted")
                .isNull();
    }

    @Test
    @DisplayName("Phase 304 case 7: when the surrounding transaction rolls back before commit, the "
            + "registered salon-catalogue eviction callback is discarded and never runs")
    void should_neverEvictSalonCatalog_when_transactionRollsBackBeforeCommit() {
        UUID salonId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID serviceTypeId = UUID.randomUUID();
        stubBulkCreateHappyPath(salonId, masterId, serviceTypeId);
        when(masterServiceRepository.findBookableAssignmentsBySalon(salonId)).thenReturn(List.of());

        // Warm the catalogue cache.
        serviceCatalogService.getSalonServiceCatalog(salonId);

        TransactionSynchronizationManager.initSynchronization();
        try {
            serviceCatalogService.bulkCreateSalonMasterServices(
                    salonId, masterId, oneItemBulkRequest(serviceTypeId));

            List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
            assertThat(syncs)
                    .as("the callback must be registered even though the surrounding transaction "
                            + "will now roll back")
                    .isNotEmpty();

            // Simulate a rollback: afterCommit is deliberately never invoked here — Spring would
            // instead call afterCompletion(STATUS_ROLLED_BACK), which this callback does not
            // override, so nothing evicts.
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        assertThat(cacheManager.getCache("salon-service-catalog").get(salonId))
                .as("a rolled-back transaction must never evict the cache — the pre-write entry survives")
                .isNotNull();
    }

    /**
     * Phase 304 case 8. Asserts the two evictions {@code bulkCreateSalonMasterServices} actually
     * fires today: {@code masterServices} (pre-existing, D3 — must stay unchanged) and
     * {@code salon-service-catalog} (new, D1).
     *
     * <p><b>Deliberately does NOT assert {@code available-slots} or the bookable-verdict cache.</b>
     * Unlike {@code deactivateServiceDefinition} (which removes a service from the bookable set and
     * therefore must invalidate any cached slot list that could now be wrong), a bulk-CREATE only
     * ADDS a brand-new service — no existing cached {@code available-slots}/bookable-verdict entry
     * for THIS master can have gone stale, because no cache entry for the new service existed
     * before this call. Reading D3's own wording ("the master-prefix sweeps... still fire alongside
     * the new salon eviction") as requiring those two sweeps HERE as well would be asserting
     * production behaviour that does not exist on this write path — grepping
     * {@code bulkCreateForMaster} confirms it only ever called {@code evictMasterServicesCache},
     * never {@code evictAvailableSlotsCache}/{@code evictBookableFutureSlotsCache}, both before and
     * after this phase. Flagged in the phase 304 completion report rather than silently asserting a
     * behaviour this method has never had.
     */
    @Test
    @DisplayName("Phase 304 case 8: bulkCreateSalonMasterServices still evicts masterServices (D3, "
            + "unchanged) alongside the new salon-catalogue eviction (D1) — both recompute on the "
            + "very next read")
    void should_evictBothMasterServicesAndSalonCatalog_when_bulkCreateSalonMasterServicesCalled() {
        UUID salonId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID serviceTypeId = UUID.randomUUID();
        stubBulkCreateHappyPath(salonId, masterId, serviceTypeId);
        when(masterServiceRepository.findBookableAssignmentsBySalon(salonId)).thenReturn(List.of());
        when(masterServiceRepository.findByMasterIdAndIsActiveTrueWithGraph(eq(masterId), any(Pageable.class)))
                .thenReturn(List.of());

        // Warm both caches.
        serviceCatalogService.getMasterServices(masterId);
        serviceCatalogService.getSalonServiceCatalog(salonId);

        serviceCatalogService.bulkCreateSalonMasterServices(salonId, masterId, oneItemBulkRequest(serviceTypeId));

        // Both must recompute on the very next read.
        serviceCatalogService.getMasterServices(masterId);
        serviceCatalogService.getSalonServiceCatalog(salonId);

        verify(masterServiceRepository, times(2))
                .findByMasterIdAndIsActiveTrueWithGraph(eq(masterId), any(Pageable.class));
        verify(masterServiceRepository, times(2)).findBookableAssignmentsBySalon(salonId);
    }
}
