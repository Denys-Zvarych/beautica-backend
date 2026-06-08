package com.beautica.service.service;

import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.master.repository.MasterRepository;
import com.beautica.salon.entity.Salon;
import com.beautica.service.entity.CatalogCategory;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.OwnerType;
import com.beautica.service.entity.PlatformCategory;
import com.beautica.service.entity.PriceType;
import com.beautica.service.entity.ServiceDefinition;
import com.beautica.service.entity.ServiceType;
import com.beautica.service.entity.ServiceTypeSuggestion;
import com.beautica.service.repository.CatalogCategoryRepository;
import com.beautica.service.repository.MasterServiceRepository;
import com.beautica.service.repository.PlatformCategoryRepository;
import com.beautica.service.repository.ServiceRepository;
import com.beautica.service.repository.ServiceTypeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ServiceTypePromotionService} — the Phase 16.9.1 promotion writer
 * that materializes an APPROVED {@link ServiceTypeSuggestion} into a real catalog
 * {@link ServiceType} + a master-owned DRAFT {@link ServiceDefinition} + an inactive
 * {@link MasterServiceAssignment}.
 *
 * <p>The full branch matrix from the 16.9.1 / 16.9.3 owed-test plan:
 * <ul>
 *   <li>requester is an INDEPENDENT_MASTER → type + draft (isDraft, !isActive, ₴0/FIXED/0-min)
 *       + inactive assignment, definition owned by the master;</li>
 *   <li>requester is a SALON master/owner → definition owned by the SALON (owner_id = salon.id);</li>
 *   <li>null / non-master / deleted requester → catalog-only, no draft/assignment, never throws;</li>
 *   <li>unmapped platform category (service_category_id null) → draft with serviceType = null;</li>
 *   <li>existing matching ServiceType → reused (no duplicate type);</li>
 *   <li>double-promote re-entry → {@code existsDraftAssignment} short-circuits;</li>
 *   <li>cache eviction runs (inline when no tx is active).</li>
 * </ul>
 *
 * <p>No Spring context — pure {@code @ExtendWith(MockitoExtension.class)}. The afterCommit
 * eviction hook degrades to an inline {@code doEvict} when no transaction synchronization is
 * active (the direct-invocation case here), so the cache-evict assertion is exercisable.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ServiceTypePromotionService — unit")
class ServiceTypePromotionServiceTest {

    private static final String CATEGORY = "HAIRDRESSING";
    private static final String SUGGESTED = "Балаяж";
    private static final UUID BUCKET_ID = UUID.fromString("11111111-0004-0000-0000-000000000000");

    @Mock private ServiceTypeRepository serviceTypeRepository;
    @Mock private ServiceRepository serviceRepository;
    @Mock private MasterServiceRepository masterServiceRepository;
    @Mock private MasterRepository masterRepository;
    @Mock private PlatformCategoryRepository platformCategoryRepository;
    @Mock private CatalogCategoryRepository catalogCategoryRepository;
    @Mock private CacheManager cacheManager;
    @Mock private Cache masterServicesCache;

    // Real SlugGenerator — slug generation is pure logic with an injected existence
    // predicate; mocking it would let a no-op promote pass. We exercise the real pipeline.
    private final SlugGenerator slugGenerator = new SlugGenerator();

    private ServiceTypePromotionService promotionService;

    @BeforeEach
    void setUp() {
        promotionService = new ServiceTypePromotionService(
                serviceTypeRepository, serviceRepository, masterServiceRepository,
                masterRepository, platformCategoryRepository, catalogCategoryRepository,
                slugGenerator, cacheManager);
    }

    // ── fixtures ───────────────────────────────────────────────────────────────

    private ServiceTypeSuggestion approvedSuggestion(UUID requesterId) {
        return ServiceTypeSuggestion.ofPending(CATEGORY, SUGGESTED, "desc", requesterId, "hash", null);
    }

    private Master independentMaster(UUID userId) {
        return Master.builder()
                .id(UUID.randomUUID())
                .masterType(MasterType.INDEPENDENT_MASTER)
                .isActive(true)
                .build();
    }

    private Master salonMaster(UUID userId, UUID salonId, MasterType type) {
        Salon salon = new Salon();
        ReflectionTestUtils.setField(salon, "id", salonId);
        return Master.builder()
                .id(UUID.randomUUID())
                .salon(salon)
                .masterType(type)
                .isActive(true)
                .build();
    }

    private CatalogCategory bucket() {
        return CatalogCategory.builder()
                .id(BUCKET_ID).nameUk("Волосся").nameEn("Hair").sortOrder(4).build();
    }

    private PlatformCategory mappedPlatformCategory() {
        PlatformCategory pc = PlatformCategory.ofApproved(CATEGORY, "Перукарські послуги");
        ReflectionTestUtils.setField(pc, "serviceCategoryId", BUCKET_ID);
        return pc;
    }

    /** Stubs the "no existing type, bucket resolves" common path used by most happy cases. */
    private void stubNovelTypeWithBucket() {
        when(serviceTypeRepository.findFirstActiveByPlatformCategoryNameAndNameUkIgnoreCase(CATEGORY, SUGGESTED))
                .thenReturn(Optional.empty());
        when(platformCategoryRepository.findByName(CATEGORY)).thenReturn(Optional.of(mappedPlatformCategory()));
        when(catalogCategoryRepository.findById(BUCKET_ID)).thenReturn(Optional.of(bucket()));
        when(serviceTypeRepository.existsBySlug(anyString())).thenReturn(false);
        when(serviceTypeRepository.save(any(ServiceType.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private void stubCacheHit() {
        when(cacheManager.getCache("masterServices")).thenReturn(masterServicesCache);
    }

    // ── independent master → full materialization ──────────────────────────────

    @Test
    @DisplayName("independent master → ServiceType + DRAFT definition (isDraft, !isActive, ₴0/FIXED/0-min) + inactive assignment owned by the master")
    void should_createTypeDraftAndInactiveAssignment_when_requesterIsIndependentMaster() {
        UUID userId = UUID.randomUUID();
        Master master = independentMaster(userId);
        ServiceTypeSuggestion suggestion = approvedSuggestion(userId);

        when(masterRepository.findByUserId(userId)).thenReturn(Optional.of(master));
        stubNovelTypeWithBucket();
        when(masterServiceRepository.existsDraftAssignment(master.getId(), SUGGESTED, CATEGORY)).thenReturn(false);
        when(serviceRepository.save(any(ServiceDefinition.class))).thenAnswer(inv -> inv.getArgument(0));
        stubCacheHit();

        promotionService.promote(suggestion);

        // ServiceType created under the resolved bucket, name mirrored uk == en, active.
        ArgumentCaptor<ServiceType> typeCaptor = ArgumentCaptor.forClass(ServiceType.class);
        verify(serviceTypeRepository).save(typeCaptor.capture());
        ServiceType type = typeCaptor.getValue();
        assertThat(type.getNameUk()).isEqualTo(SUGGESTED);
        assertThat(type.getNameEn()).as("name_en mirrors name_uk (V17 NOT NULL)").isEqualTo(SUGGESTED);
        assertThat(type.getPlatformCategoryName()).isEqualTo(CATEGORY);
        assertThat(type.getCategory().getId()).as("type bound to the resolved System-A bucket").isEqualTo(BUCKET_ID);
        assertThat(type.isActive()).isTrue();
        assertThat(type.getSlug())
                .as("slug transliterated + DB-CHECK shape").matches("^[a-z0-9]+(?:-[a-z0-9]+)*$");

        // Draft definition: sentinel pricing, draft + inactive, owned by the INDEPENDENT master.
        ArgumentCaptor<ServiceDefinition> defCaptor = ArgumentCaptor.forClass(ServiceDefinition.class);
        verify(serviceRepository).save(defCaptor.capture());
        ServiceDefinition draft = defCaptor.getValue();
        assertThat(draft.isDraft()).as("materialized service is a draft").isTrue();
        assertThat(draft.isActive()).as("draft is inactive (excluded from search + browse)").isFalse();
        assertThat(draft.getPriceType()).isEqualTo(PriceType.FIXED);
        assertThat(draft.getBasePrice()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(draft.getPriceMax()).isNull();
        assertThat(draft.getBaseDurationMinutes()).isZero();
        assertThat(draft.getBufferMinutesAfter()).isZero();
        assertThat(draft.getName()).isEqualTo(SUGGESTED);
        assertThat(draft.getCategory()).isEqualTo(CATEGORY);
        assertThat(draft.getServiceType()).as("draft links the just-created type").isSameAs(type);
        assertThat(draft.getOwnerType()).isEqualTo(OwnerType.INDEPENDENT_MASTER);
        assertThat(draft.getOwnerId()).as("independent master owns its own definition").isEqualTo(master.getId());

        // Inactive assignment links master ↔ draft.
        ArgumentCaptor<MasterServiceAssignment> asgCaptor = ArgumentCaptor.forClass(MasterServiceAssignment.class);
        verify(masterServiceRepository).save(asgCaptor.capture());
        MasterServiceAssignment assignment = asgCaptor.getValue();
        assertThat(assignment.getMaster()).isSameAs(master);
        assertThat(assignment.getServiceDefinition()).isSameAs(draft);
        assertThat(assignment.isActive()).as("assignment is inactive — draft never surfaces").isFalse();

        // Cache evicted for this master (inline, no active tx).
        verify(masterServicesCache).evict(master.getId());
    }

    // ── salon master/owner → salon-owned definition ────────────────────────────

    @Test
    @DisplayName("salon master → draft definition owned by the salon (owner_type=SALON, owner_id=salon.id)")
    void should_attachDefinitionToSalon_when_requesterIsSalonMaster() {
        UUID userId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        Master master = salonMaster(userId, salonId, MasterType.SALON_MASTER);
        ServiceTypeSuggestion suggestion = approvedSuggestion(userId);

        when(masterRepository.findByUserId(userId)).thenReturn(Optional.of(master));
        stubNovelTypeWithBucket();
        when(masterServiceRepository.existsDraftAssignment(master.getId(), SUGGESTED, CATEGORY)).thenReturn(false);
        when(serviceRepository.save(any(ServiceDefinition.class))).thenAnswer(inv -> inv.getArgument(0));
        stubCacheHit();

        promotionService.promote(suggestion);

        ArgumentCaptor<ServiceDefinition> defCaptor = ArgumentCaptor.forClass(ServiceDefinition.class);
        verify(serviceRepository).save(defCaptor.capture());
        ServiceDefinition draft = defCaptor.getValue();
        assertThat(draft.getOwnerType()).isEqualTo(OwnerType.SALON);
        assertThat(draft.getOwnerId())
                .as("salon-employed master → the owning salon owns the definition").isEqualTo(salonId);
        // Assignment still attached to the requesting master (so it shows in their list).
        verify(masterServiceRepository).save(any(MasterServiceAssignment.class));
    }

    @Test
    @DisplayName("salon owner → draft definition owned by the salon (same SALON branch)")
    void should_attachDefinitionToSalon_when_requesterIsSalonOwnerMaster() {
        UUID userId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        Master master = salonMaster(userId, salonId, MasterType.SALON_OWNER);
        ServiceTypeSuggestion suggestion = approvedSuggestion(userId);

        when(masterRepository.findByUserId(userId)).thenReturn(Optional.of(master));
        stubNovelTypeWithBucket();
        when(masterServiceRepository.existsDraftAssignment(master.getId(), SUGGESTED, CATEGORY)).thenReturn(false);
        when(serviceRepository.save(any(ServiceDefinition.class))).thenAnswer(inv -> inv.getArgument(0));
        stubCacheHit();

        promotionService.promote(suggestion);

        ArgumentCaptor<ServiceDefinition> defCaptor = ArgumentCaptor.forClass(ServiceDefinition.class);
        verify(serviceRepository).save(defCaptor.capture());
        assertThat(defCaptor.getValue().getOwnerType()).isEqualTo(OwnerType.SALON);
        assertThat(defCaptor.getValue().getOwnerId()).isEqualTo(salonId);
    }

    // ── catalog-only paths (null / non-master / deleted requester) ──────────────

    @Test
    @DisplayName("null requester → catalog-only: ServiceType created, NO draft/assignment, never throws")
    void should_createTypeOnly_when_requesterIdIsNull() {
        ServiceTypeSuggestion suggestion = approvedSuggestion(null);
        stubNovelTypeWithBucket();

        assertThatCode(() -> promotionService.promote(suggestion)).doesNotThrowAnyException();

        verify(serviceTypeRepository).save(any(ServiceType.class));
        verify(masterRepository, never()).findByUserId(any());
        verify(serviceRepository, never()).save(any(ServiceDefinition.class));
        verify(masterServiceRepository, never()).save(any(MasterServiceAssignment.class));
        verifyNoInteractions(cacheManager);
    }

    @Test
    @DisplayName("requester resolves to no master (deleted/non-master) → catalog-only, no draft, never throws")
    void should_createTypeOnly_when_requesterIsNotAMaster() {
        UUID userId = UUID.randomUUID();
        ServiceTypeSuggestion suggestion = approvedSuggestion(userId);
        when(masterRepository.findByUserId(userId)).thenReturn(Optional.empty());
        stubNovelTypeWithBucket();

        assertThatCode(() -> promotionService.promote(suggestion)).doesNotThrowAnyException();

        verify(serviceTypeRepository).save(any(ServiceType.class));
        verify(serviceRepository, never()).save(any(ServiceDefinition.class));
        verify(masterServiceRepository, never()).save(any(MasterServiceAssignment.class));
        verify(masterServiceRepository, never()).existsDraftAssignment(any(), anyString(), anyString());
        verifyNoInteractions(cacheManager);
    }

    // ── unmapped platform category → draft with serviceType == null ────────────

    @Test
    @DisplayName("unmapped platform category (service_category_id null) → draft created with serviceType=null, no NPE")
    void should_createDraftWithNullServiceType_when_platformCategoryHasNoBucket() {
        UUID userId = UUID.randomUUID();
        Master master = independentMaster(userId);
        ServiceTypeSuggestion suggestion = approvedSuggestion(userId);

        when(masterRepository.findByUserId(userId)).thenReturn(Optional.of(master));
        when(serviceTypeRepository.findFirstActiveByPlatformCategoryNameAndNameUkIgnoreCase(CATEGORY, SUGGESTED))
                .thenReturn(Optional.empty());
        // Platform category present but UNMAPPED (service_category_id null).
        when(platformCategoryRepository.findByName(CATEGORY))
                .thenReturn(Optional.of(PlatformCategory.ofApproved(CATEGORY, "x")));
        when(masterServiceRepository.existsDraftAssignment(master.getId(), SUGGESTED, CATEGORY)).thenReturn(false);
        when(serviceRepository.save(any(ServiceDefinition.class))).thenAnswer(inv -> inv.getArgument(0));
        stubCacheHit();

        promotionService.promote(suggestion);

        // No ServiceType row created — bucket unresolved.
        verify(serviceTypeRepository, never()).save(any(ServiceType.class));
        verify(catalogCategoryRepository, never()).findById(any());
        // Draft still created carrying the suggested name, with a null type link.
        ArgumentCaptor<ServiceDefinition> defCaptor = ArgumentCaptor.forClass(ServiceDefinition.class);
        verify(serviceRepository).save(defCaptor.capture());
        ServiceDefinition draft = defCaptor.getValue();
        assertThat(draft.getServiceType()).as("unmapped category → draft.serviceType is null").isNull();
        assertThat(draft.getName()).isEqualTo(SUGGESTED);
        assertThat(draft.isDraft()).isTrue();
        verify(masterServiceRepository).save(any(MasterServiceAssignment.class));
    }

    // ── existing matching ServiceType → reuse, no duplicate ────────────────────

    @Test
    @DisplayName("matching active ServiceType exists → REUSED (no duplicate type, no bucket lookup)")
    void should_reuseExistingServiceType_when_matchingTypeExists() {
        UUID userId = UUID.randomUUID();
        Master master = independentMaster(userId);
        ServiceTypeSuggestion suggestion = approvedSuggestion(userId);

        ServiceType existing = ServiceType.builder()
                .id(UUID.randomUUID()).category(bucket()).nameUk(SUGGESTED).nameEn(SUGGESTED)
                .slug("balaiazh").active(true).platformCategoryName(CATEGORY).build();

        when(masterRepository.findByUserId(userId)).thenReturn(Optional.of(master));
        when(serviceTypeRepository.findFirstActiveByPlatformCategoryNameAndNameUkIgnoreCase(CATEGORY, SUGGESTED))
                .thenReturn(Optional.of(existing));
        when(masterServiceRepository.existsDraftAssignment(master.getId(), SUGGESTED, CATEGORY)).thenReturn(false);
        when(serviceRepository.save(any(ServiceDefinition.class))).thenAnswer(inv -> inv.getArgument(0));
        stubCacheHit();

        promotionService.promote(suggestion);

        // No new type created, no bucket resolution attempted.
        verify(serviceTypeRepository, never()).save(any(ServiceType.class));
        verifyNoInteractions(platformCategoryRepository, catalogCategoryRepository);
        // Draft links the reused type.
        ArgumentCaptor<ServiceDefinition> defCaptor = ArgumentCaptor.forClass(ServiceDefinition.class);
        verify(serviceRepository).save(defCaptor.capture());
        assertThat(defCaptor.getValue().getServiceType()).isSameAs(existing);
    }

    // ── double-promote / re-entry guard ────────────────────────────────────────

    @Test
    @DisplayName("re-entry: existsDraftAssignment true → short-circuits, no duplicate draft/assignment/eviction")
    void should_shortCircuit_when_draftAlreadyExists() {
        UUID userId = UUID.randomUUID();
        Master master = independentMaster(userId);
        ServiceTypeSuggestion suggestion = approvedSuggestion(userId);

        when(masterRepository.findByUserId(userId)).thenReturn(Optional.of(master));
        stubNovelTypeWithBucket();
        // Draft already present from a prior promote.
        when(masterServiceRepository.existsDraftAssignment(master.getId(), SUGGESTED, CATEGORY)).thenReturn(true);

        promotionService.promote(suggestion);

        verify(serviceRepository, never()).save(any(ServiceDefinition.class));
        verify(masterServiceRepository, never()).save(any(MasterServiceAssignment.class));
        verifyNoInteractions(cacheManager);
    }

    // ── salon-null defensive note (LOW backlog finding) ────────────────────────

    @Test
    @DisplayName("salon-typed master with null salon → catalog-only, no NPE and no draft (LOW guard fixed)")
    void should_skipDraftCatalogOnly_when_salonMasterHasNullSalon() {
        UUID userId = UUID.randomUUID();
        // SALON_MASTER but salon == null — the salon-null guard in createDraftServiceForMaster
        // short-circuits to catalog-only instead of dereferencing master.getSalon().getId().
        Master master = Master.builder()
                .id(UUID.randomUUID()).masterType(MasterType.SALON_MASTER).salon(null).isActive(true).build();
        ServiceTypeSuggestion suggestion = approvedSuggestion(userId);

        when(masterRepository.findByUserId(userId)).thenReturn(Optional.of(master));
        stubNovelTypeWithBucket();

        assertThatCode(() -> promotionService.promote(suggestion))
                .as("salon-null SALON master is catalog-only, never NPE on approve")
                .doesNotThrowAnyException();

        verify(serviceRepository, never()).save(any(ServiceDefinition.class));
        verify(masterServiceRepository, never()).save(any(MasterServiceAssignment.class));
        verifyNoInteractions(cacheManager);
    }

    // ── cache eviction is a no-op-safe when the cache is absent ────────────────

    @Test
    @DisplayName("missing masterServices cache → eviction is a safe no-op (no NPE)")
    void should_notThrow_when_masterServicesCacheAbsent() {
        UUID userId = UUID.randomUUID();
        Master master = independentMaster(userId);
        ServiceTypeSuggestion suggestion = approvedSuggestion(userId);

        when(masterRepository.findByUserId(userId)).thenReturn(Optional.of(master));
        stubNovelTypeWithBucket();
        when(masterServiceRepository.existsDraftAssignment(master.getId(), SUGGESTED, CATEGORY)).thenReturn(false);
        when(serviceRepository.save(any(ServiceDefinition.class))).thenAnswer(inv -> inv.getArgument(0));
        when(cacheManager.getCache("masterServices")).thenReturn(null);

        assertThatCode(() -> promotionService.promote(suggestion)).doesNotThrowAnyException();
        verify(cacheManager).getCache("masterServices");
        verify(masterServicesCache, never()).evict(any());
    }
}
