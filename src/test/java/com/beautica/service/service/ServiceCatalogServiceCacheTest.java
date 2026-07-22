package com.beautica.service.service;

import com.beautica.config.CacheConfig;
import com.beautica.master.entity.Master;
import com.beautica.master.repository.MasterRepository;
import com.beautica.notification.EmailService;
import com.beautica.salon.repository.SalonRepository;
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
import org.springframework.cache.interceptor.SimpleKey;

import org.springframework.data.domain.Pageable;

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
                CacheConfig.class},
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
    // Phase 23.x (perf/security #2): ServiceCatalogService evicts the salon-service-catalog cache via
    // this collaborator on every definition mutation. It is a REAL bean here (on the @SpringBootTest
    // classes list) so its @CacheEvict fires through the AOP proxy — the salon-catalogue eviction tests
    // below assert an actual recompute, not an annotation-count. @Autowired only to make its presence
    // explicit; ServiceCatalogService injects it by constructor.
    @Autowired SalonCatalogCacheEvictor salonCatalogCacheEvictor;

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

    @Test
    @DisplayName("deactivateServiceDefinition evicts available-slots cache entries for affected masters")
    void should_evictAvailableSlotsCache_when_deactivateServiceDefinitionCalled() {
        // Arrange — pre-populate an available-slots entry whose key contains the affected
        // master UUID as the first element. The production eviction scans Caffeine's
        // asMap() for SimpleKey entries whose toString() contains "[<masterId>,".
        UUID masterId = UUID.randomUUID();
        UUID serviceDefId = UUID.randomUUID();
        UUID someServiceId = UUID.randomUUID();
        LocalDate someDate = LocalDate.of(2026, 6, 1);

        // Simulate a slot-cache entry placed by SlotCalculationService's @Cacheable method.
        // Key shape: SimpleKey[masterId, date, serviceId] as Spring would produce it.
        var slotsCache = cacheManager.getCache("available-slots");
        var cacheKey = new SimpleKey(masterId, someDate, someServiceId);
        slotsCache.put(cacheKey, List.of("09:00", "10:00"));

        when(masterServiceRepository.findMasterIdsByServiceDefinitionId(serviceDefId))
                .thenReturn(List.of(masterId));
        when(serviceRepository.deactivateById(serviceDefId)).thenReturn(1);

        // Act — no active Spring transaction here; eviction runs immediately in the else-branch.
        serviceCatalogService.deactivateServiceDefinition(UUID.randomUUID(), serviceDefId);

        // Assert — the cache entry for the affected master must be gone.
        assertThat(slotsCache.get(cacheKey)).isNull();
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
}
