package com.beautica.service.service;

import com.beautica.service.entity.CatalogCategory;
import com.beautica.service.entity.PlatformCategory;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ServiceTypePromotionService} — the Phase 16.9 promotion writer.
 *
 * <p><strong>Option A (catalog-only).</strong> This reverses the earlier Option B behaviour:
 * approving a suggestion materializes ONLY a catalog {@link ServiceType} (so it surfaces in
 * the {@code GET /service-types?categoryName=} dropdown). It NEVER creates a
 * {@code ServiceDefinition} (draft) or a {@code MasterServiceAssignment}. The branch matrix:
 * <ul>
 *   <li>mapped platform category → type created under the mapped System-A bucket;</li>
 *   <li>unmapped platform category (service_category_id null) → type created under the
 *       permanent "Other" System-A bucket (V13 fallback);</li>
 *   <li>existing matching active type → reused, no duplicate;</li>
 *   <li>requester identity is irrelevant — no master/service/assignment repositories are
 *       ever touched.</li>
 * </ul>
 *
 * <p>No Spring context — pure {@code @ExtendWith(MockitoExtension.class)}. The
 * {@code serviceRepository} / {@code masterServiceRepository} mocks exist only to assert
 * they are NEVER invoked.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ServiceTypePromotionService — unit (catalog-only)")
class ServiceTypePromotionServiceTest {

    private static final String CATEGORY = "HAIRDRESSING";
    private static final String SUGGESTED = "Балаяж";
    private static final UUID BUCKET_ID = UUID.fromString("11111111-0004-0000-0000-000000000000");
    private static final UUID OTHER_BUCKET_ID = UUID.fromString("11111111-0008-0000-0000-000000000000");

    @Mock private ServiceTypeRepository serviceTypeRepository;
    @Mock private ServiceRepository serviceRepository;
    @Mock private MasterServiceRepository masterServiceRepository;
    @Mock private PlatformCategoryRepository platformCategoryRepository;
    @Mock private CatalogCategoryRepository catalogCategoryRepository;

    // Real SlugGenerator — slug generation is pure logic with an injected existence
    // predicate; mocking it would let a no-op promote pass. We exercise the real pipeline.
    private final SlugGenerator slugGenerator = new SlugGenerator();

    private ServiceTypePromotionService promotionService;

    @BeforeEach
    void setUp() {
        promotionService = new ServiceTypePromotionService(
                serviceTypeRepository, platformCategoryRepository,
                catalogCategoryRepository, slugGenerator);
    }

    // ── fixtures ───────────────────────────────────────────────────────────────

    private ServiceTypeSuggestion approvedSuggestion(UUID requesterId) {
        return ServiceTypeSuggestion.ofPending(CATEGORY, SUGGESTED, "desc", requesterId, "hash", null);
    }

    private CatalogCategory bucket(UUID id, String uk, String en) {
        return CatalogCategory.builder()
                .id(id).nameUk(uk).nameEn(en).sortOrder(4).build();
    }

    private PlatformCategory mappedPlatformCategory() {
        PlatformCategory pc = PlatformCategory.ofApproved(CATEGORY, "Перукарські послуги");
        ReflectionTestUtils.setField(pc, "serviceCategoryId", BUCKET_ID);
        return pc;
    }

    private void stubNovelType() {
        when(serviceTypeRepository.findFirstActiveByPlatformCategoryNameAndNameUkIgnoreCase(CATEGORY, SUGGESTED))
                .thenReturn(Optional.empty());
        when(serviceTypeRepository.existsBySlug(anyString())).thenReturn(false);
        when(serviceTypeRepository.save(any(ServiceType.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ── mapped category → type under the mapped bucket ──────────────────────────

    @Test
    @DisplayName("mapped platform category → ServiceType created under the mapped System-A bucket; no master service ever written")
    void should_createServiceTypeUnderMappedBucket_when_categoryIsMapped() {
        ServiceTypeSuggestion suggestion = approvedSuggestion(UUID.randomUUID());
        stubNovelType();
        when(platformCategoryRepository.findByName(CATEGORY)).thenReturn(Optional.of(mappedPlatformCategory()));
        when(catalogCategoryRepository.findById(BUCKET_ID)).thenReturn(Optional.of(bucket(BUCKET_ID, "Волосся", "Hair")));

        promotionService.promote(suggestion);

        ArgumentCaptor<ServiceType> typeCaptor = ArgumentCaptor.forClass(ServiceType.class);
        verify(serviceTypeRepository).save(typeCaptor.capture());
        ServiceType type = typeCaptor.getValue();
        assertThat(type.getNameUk()).isEqualTo(SUGGESTED);
        assertThat(type.getNameEn()).as("name_en mirrors name_uk (V17 NOT NULL)").isEqualTo(SUGGESTED);
        assertThat(type.getPlatformCategoryName()).isEqualTo(CATEGORY);
        assertThat(type.getCategory().getId()).as("type bound to the mapped System-A bucket").isEqualTo(BUCKET_ID);
        assertThat(type.isActive()).isTrue();
        assertThat(type.getSlug())
                .as("slug transliterated + DB-CHECK shape").matches("^[a-z0-9]+(?:-[a-z0-9]+)*$");

        verifyNoInteractions(serviceRepository, masterServiceRepository);
    }

    // ── unmapped category → type under the permanent "Other" bucket ─────────────

    @Test
    @DisplayName("unmapped platform category (service_category_id null) → ServiceType created under the permanent 'Other' bucket; no master service")
    void should_createServiceTypeUnderOtherBucket_when_platformCategoryHasNoBucket() {
        ServiceTypeSuggestion suggestion = approvedSuggestion(UUID.randomUUID());
        stubNovelType();
        // Platform category present but UNMAPPED (service_category_id null).
        when(platformCategoryRepository.findByName(CATEGORY))
                .thenReturn(Optional.of(PlatformCategory.ofApproved(CATEGORY, "x")));
        when(catalogCategoryRepository.findById(OTHER_BUCKET_ID))
                .thenReturn(Optional.of(bucket(OTHER_BUCKET_ID, "Інше", "Other")));

        promotionService.promote(suggestion);

        ArgumentCaptor<ServiceType> typeCaptor = ArgumentCaptor.forClass(ServiceType.class);
        verify(serviceTypeRepository).save(typeCaptor.capture());
        ServiceType type = typeCaptor.getValue();
        assertThat(type.getCategory().getId())
                .as("unmapped category → type bound to the permanent 'Other' fallback bucket")
                .isEqualTo(OTHER_BUCKET_ID);
        assertThat(type.getNameUk()).isEqualTo(SUGGESTED);
        assertThat(type.getPlatformCategoryName()).isEqualTo(CATEGORY);
        assertThat(type.isActive()).isTrue();

        verifyNoInteractions(serviceRepository, masterServiceRepository);
    }

    // ── existing matching ServiceType → reuse, no duplicate ────────────────────

    @Test
    @DisplayName("matching active ServiceType exists → REUSED (no duplicate type, no bucket lookup, no master service)")
    void should_reuseExistingServiceType_when_matchingTypeExists() {
        ServiceTypeSuggestion suggestion = approvedSuggestion(UUID.randomUUID());

        ServiceType existing = ServiceType.builder()
                .id(UUID.randomUUID()).category(bucket(BUCKET_ID, "Волосся", "Hair"))
                .nameUk(SUGGESTED).nameEn(SUGGESTED)
                .slug("balaiazh").active(true).platformCategoryName(CATEGORY).build();

        when(serviceTypeRepository.findFirstActiveByPlatformCategoryNameAndNameUkIgnoreCase(CATEGORY, SUGGESTED))
                .thenReturn(Optional.of(existing));

        promotionService.promote(suggestion);

        // No new type created, no bucket resolution attempted, no master service.
        verify(serviceTypeRepository, never()).save(any(ServiceType.class));
        verifyNoInteractions(platformCategoryRepository, catalogCategoryRepository,
                serviceRepository, masterServiceRepository);
    }

    // ── requester identity is irrelevant under Option A ─────────────────────────

    @Test
    @DisplayName("null requester → still creates the ServiceType, never throws, never touches master service repositories")
    void should_createTypeAndNeverThrow_when_requesterIdIsNull() {
        ServiceTypeSuggestion suggestion = approvedSuggestion(null);
        stubNovelType();
        when(platformCategoryRepository.findByName(CATEGORY)).thenReturn(Optional.of(mappedPlatformCategory()));
        when(catalogCategoryRepository.findById(BUCKET_ID)).thenReturn(Optional.of(bucket(BUCKET_ID, "Волосся", "Hair")));

        assertThatCode(() -> promotionService.promote(suggestion)).doesNotThrowAnyException();

        verify(serviceTypeRepository).save(any(ServiceType.class));
        verifyNoInteractions(serviceRepository, masterServiceRepository);
    }
}
