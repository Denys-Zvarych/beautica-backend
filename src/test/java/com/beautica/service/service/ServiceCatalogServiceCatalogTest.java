package com.beautica.service.service;

import com.beautica.master.repository.MasterRepository;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.service.dto.CatalogCategoryResponse;
import com.beautica.service.dto.PlatformServiceTypeResponse;
import com.beautica.service.dto.ServiceTypeResponse;
import com.beautica.service.dto.SuggestServiceTypeRequest;
import com.beautica.service.entity.CatalogCategory;
import com.beautica.service.entity.ServiceType;
import com.beautica.service.repository.MasterServiceRepository;
import com.beautica.service.repository.PlatformCategoryRepository;
import com.beautica.service.repository.ServiceRepository;
import com.beautica.service.repository.ServiceTypeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;

import java.util.List;
import java.util.UUID;

import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.service.entity.PlatformCategoryStatus;
import org.springframework.http.HttpStatus;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ServiceCatalogService — catalog methods unit")
class ServiceCatalogServiceCatalogTest {

    @Mock private ServiceRepository serviceRepository;
    @Mock private MasterServiceRepository masterServiceRepository;
    @Mock private SalonRepository salonRepository;
    @Mock private MasterRepository masterRepository;
    @Mock private CatalogCategoryLookup catalogCategoryLookup;
    @Mock private PlatformCategoryRepository platformCategoryRepository;
    @Mock private ServiceTypeSuggestionService serviceTypeSuggestionService;
    @Mock private ServiceTypeLookup serviceTypeLookup;
    @Mock private ServiceTypeSearchService serviceTypeSearchService;
    @Mock private ServiceTypeRepository serviceTypeRepository;
    @Mock private CacheManager cacheManager;
    @Mock private com.beautica.common.security.AuthorizationService authz;

    private ServiceCatalogService service;

    @BeforeEach
    void setUp() {
        service = new ServiceCatalogService(
                serviceRepository,
                masterServiceRepository,
                salonRepository,
                masterRepository,
                catalogCategoryLookup,
                platformCategoryRepository,
                serviceTypeSuggestionService,
                serviceTypeLookup,
                serviceTypeSearchService,
                serviceTypeRepository,
                cacheManager,
                authz
        );
    }

    // ── getCategories ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("returns categories ordered by sortOrder from repository")
    void should_returnCategories_when_repositoryReturnsSortedList() {
        var cat1 = new CatalogCategoryResponse(UUID.randomUUID(), "Нігті", "Nails", 1);
        var cat2 = new CatalogCategoryResponse(UUID.randomUUID(), "Брови", "Brows", 2);

        when(catalogCategoryLookup.getAll()).thenReturn(List.of(cat1, cat2));

        List<CatalogCategoryResponse> result = service.getCategories();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).nameUk()).isEqualTo("Нігті");
        assertThat(result.get(1).nameUk()).isEqualTo("Брови");
    }

    @Test
    @DisplayName("returns empty list when no categories exist")
    void should_returnEmptyList_when_noCategoriesExist() {
        when(catalogCategoryLookup.getAll()).thenReturn(List.of());

        List<CatalogCategoryResponse> result = service.getCategories();

        assertThat(result).isEmpty();
    }

    // ── searchServiceTypes — all active (no params) ───────────────────────────

    @Test
    @DisplayName("returns all active types when categoryId and q are both null")
    void should_returnAllActive_when_noCategoryIdAndNoQuery() {
        var category = mock(CatalogCategory.class);
        when(category.getId()).thenReturn(UUID.randomUUID());

        var type = mock(ServiceType.class);
        when(type.getId()).thenReturn(UUID.randomUUID());
        when(type.getCategory()).thenReturn(category);
        when(type.getNameUk()).thenReturn("Гель-лак");
        when(type.getNameEn()).thenReturn("Gel Polish");
        when(type.getSlug()).thenReturn("gel-polish");

        when(serviceTypeLookup.getByCategory(null)).thenReturn(List.of(type));

        List<ServiceTypeResponse> result = service.searchServiceTypes(null, null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).nameUk()).isEqualTo("Гель-лак");
        verify(serviceTypeLookup).getByCategory(null);
    }

    // ── searchServiceTypes — by categoryId ────────────────────────────────────

    @Test
    @DisplayName("returns types for category when categoryId provided and q is null")
    void should_returnTypesForCategory_when_categoryIdProvidedAndQIsNull() {
        UUID categoryId = UUID.randomUUID();

        var category = mock(CatalogCategory.class);
        when(category.getId()).thenReturn(categoryId);

        var type = mock(ServiceType.class);
        when(type.getId()).thenReturn(UUID.randomUUID());
        when(type.getCategory()).thenReturn(category);
        when(type.getNameUk()).thenReturn("Манікюр");
        when(type.getNameEn()).thenReturn("Manicure");
        when(type.getSlug()).thenReturn("manicure");

        when(catalogCategoryLookup.getAll()).thenReturn(
                List.of(new CatalogCategoryResponse(categoryId, "Нігті", "Nails", 1)));
        when(serviceTypeLookup.getByCategory(categoryId)).thenReturn(List.of(type));

        List<ServiceTypeResponse> result = service.searchServiceTypes(categoryId, null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).categoryId()).isEqualTo(categoryId);
        verify(serviceTypeLookup).getByCategory(categoryId);
    }

    // ── searchServiceTypes — with q ≥ 3 chars ────────────────────────────────

    @Test
    @DisplayName("delegates to searchByName when q has 3 or more characters")
    void should_delegateToSearchByName_when_qHasThreeOrMoreChars() {
        UUID categoryId = UUID.randomUUID();
        var expected = new ServiceTypeResponse(UUID.randomUUID(), categoryId, "Манікюр класичний", "Classic Manicure", "manicure-classic");

        when(serviceTypeSearchService.searchByName("ман", null)).thenReturn(List.of(expected));

        List<ServiceTypeResponse> result = service.searchServiceTypes(null, "Ман");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).nameUk()).isEqualTo("Манікюр класичний");
        verify(serviceTypeSearchService).searchByName("ман", null);
    }

    @Test
    @DisplayName("strips whitespace and lowercases q before delegating to search service")
    void should_stripWhitespace_when_qHasLeadingOrTrailingSpaces() {
        when(serviceTypeSearchService.searchByName("гель", null)).thenReturn(List.of());

        service.searchServiceTypes(null, "  Гель  ");

        verify(serviceTypeSearchService).searchByName("гель", null);
    }

    @Test
    @DisplayName("falls back to all-active when q has fewer than 3 chars after stripping")
    void should_fallbackToAllActive_when_qHasFewerThanTwoCharsAfterStrip() {
        when(serviceTypeLookup.getByCategory(null)).thenReturn(List.of());

        List<ServiceTypeResponse> result = service.searchServiceTypes(null, "М");

        assertThat(result).isEmpty();
        verify(serviceTypeLookup).getByCategory(null);
    }

    @Test
    @DisplayName("delegates to searchByNameAndCategory when both q and categoryId are provided")
    void should_delegateToSearchByNameAndCategory_when_qAndCategoryIdBothProvided() {
        UUID categoryId = UUID.randomUUID();
        var expected = new ServiceTypeResponse(UUID.randomUUID(), categoryId, "Манікюр", "Manicure", "manicure");

        when(catalogCategoryLookup.getAll()).thenReturn(
                List.of(new CatalogCategoryResponse(categoryId, "Нігті", "Nails", 1)));
        when(serviceTypeSearchService.searchByName("ман", categoryId)).thenReturn(List.of(expected));

        List<ServiceTypeResponse> result = service.searchServiceTypes(categoryId, "Ман");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).categoryId()).isEqualTo(categoryId);
        // serviceTypeSearchService.searchByName receives the categoryId so the DB query is category-scoped
        verify(serviceTypeSearchService).searchByName("ман", categoryId);
        verify(serviceTypeSearchService, never()).searchByName(eq("ман"), eq(null));
    }

    @Test
    @DisplayName("uses plain searchByName (categoryId=null) when categoryId is null and q >= 3 chars")
    void should_useSearchByName_when_categoryIdIsNull() {
        when(serviceTypeSearchService.searchByName("ман", null)).thenReturn(List.of());

        service.searchServiceTypes(null, "Ман");

        verify(serviceTypeSearchService).searchByName("ман", null);
        // searchByName with a non-null categoryId must never be called
        verify(serviceTypeSearchService, never()).searchByName(anyString(), any(UUID.class));
    }

    // ── suggestServiceType ────────────────────────────────────────────────────

    @Test
    @DisplayName("delegates to suggestion service with the validated slug, name, description and userId")
    void should_delegateToSubmitSuggestion_when_suggestServiceTypeCalled() {
        UUID userId = UUID.randomUUID();
        String categoryName = "LASH_EXTENSIONS";
        when(platformCategoryRepository.existsByNameAndActiveTrueAndStatus(
                eq(categoryName), any())).thenReturn(true);
        var request = new SuggestServiceTypeRequest("Ламінування вій", categoryName, "Детальний опис");

        service.suggestServiceType(request, userId);

        verify(serviceTypeSuggestionService).submitSuggestion(
                categoryName,
                "Ламінування вій",
                "Детальний опис",
                userId
        );
    }

    @Test
    @DisplayName("passes the suggested name through to the suggestion service")
    void should_passNameToSubmitSuggestion_when_suggestServiceTypeCalled() {
        UUID userId = UUID.randomUUID();
        String categoryName = "LASH_EXTENSIONS";
        when(platformCategoryRepository.existsByNameAndActiveTrueAndStatus(
                eq(categoryName), any())).thenReturn(true);
        var request = new SuggestServiceTypeRequest("Ламінування вій", categoryName, null);

        service.suggestServiceType(request, userId);

        // Subject composition moved into ServiceTypeSuggestionService/EmailService (Phase 16.8)
        // and is no longer observable here; at this layer we only assert the name is delegated.
        var nameCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(serviceTypeSuggestionService).submitSuggestion(
                eq(categoryName),
                nameCaptor.capture(),
                eq(null),
                eq(userId)
        );
        assertThat(nameCaptor.getValue()).isEqualTo("Ламінування вій");
    }

    @Test
    @DisplayName("delegates a null description as-is to the suggestion service")
    void should_delegateNullDescription_when_descriptionIsNull() {
        UUID userId = UUID.randomUUID();
        String categoryName = "LASH_EXTENSIONS";
        when(platformCategoryRepository.existsByNameAndActiveTrueAndStatus(
                eq(categoryName), any())).thenReturn(true);
        var request = new SuggestServiceTypeRequest("Ламінування вій", categoryName, null);

        service.suggestServiceType(request, userId);

        // The em-dash placeholder for a null description is now composed inside
        // ServiceTypeSuggestionService's email body (Phase 16.8) and is not observable
        // at this layer — we only assert the raw null is forwarded untouched.
        verify(serviceTypeSuggestionService).submitSuggestion(
                categoryName,
                "Ламінування вій",
                null,
                userId
        );
    }

    @Test
    @DisplayName("passes the requesting userId through to the suggestion service")
    void should_passUserIdToSubmitSuggestion_when_suggestServiceTypeCalled() {
        UUID userId = UUID.randomUUID();
        String categoryName = "LASH_EXTENSIONS";
        when(platformCategoryRepository.existsByNameAndActiveTrueAndStatus(
                eq(categoryName), any())).thenReturn(true);
        var request = new SuggestServiceTypeRequest("Ламінування вій", categoryName, "Опис");

        service.suggestServiceType(request, userId);

        // Rendering the userId into the admin email body moved into
        // ServiceTypeSuggestionService (Phase 16.8); here we assert the userId is delegated.
        var userIdCaptor = org.mockito.ArgumentCaptor.forClass(UUID.class);
        verify(serviceTypeSuggestionService).submitSuggestion(
                eq(categoryName),
                eq("Ламінування вій"),
                eq("Опис"),
                userIdCaptor.capture()
        );
        assertThat(userIdCaptor.getValue())
                .as("the requester userId must be delegated to the suggestion service")
                .isEqualTo(userId);
    }

    // ── suggestServiceType — Phase 16.7 System-B slug validation + hardening ────
    // The catalog authority is now platform_categories (System-B name slug), not the
    // deprecated System-A UUID. validateCategoryActive(categoryName) must run BEFORE
    // any admin email is composed: an unknown / inactive / non-APPROVED slug yields a
    // clean 400 and dispatches NO email (admin-inbox-flood protection).

    @Test
    @DisplayName("suggestServiceType — rejects an unknown slug with BusinessException(400) and dispatches NO admin email")
    void should_rejectWith400AndNotEmail_when_categoryNameUnknown() {
        UUID userId = UUID.randomUUID();
        String unknownSlug = "TOTALLY_MADE_UP";
        // platform_categories has no APPROVED+active row for this slug → existence check false.
        when(platformCategoryRepository.existsByNameAndActiveTrueAndStatus(
                eq(unknownSlug), eq(PlatformCategoryStatus.APPROVED))).thenReturn(false);
        var request = new SuggestServiceTypeRequest("Нова послуга", unknownSlug, "Опис");

        assertThatThrownBy(() -> service.suggestServiceType(request, userId))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .as("unknown System-B slug must surface as 400 BAD_REQUEST")
                        .isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("Unknown category");

        // The validation gate runs BEFORE delegating — the suggestion service is never invoked,
        // so no row is persisted and no admin notification leaks out.
        verifyNoInteractions(serviceTypeSuggestionService);
    }

    @Test
    @DisplayName("suggestServiceType — rejects an inactive/PENDING slug with 400 and dispatches NO admin email")
    void should_rejectWith400AndNotEmail_when_categoryNameInactiveOrPending() {
        UUID userId = UUID.randomUUID();
        // A PENDING (self-service request) or deactivated slug exists in the table but is
        // NOT APPROVED+active, so existsByNameAndActiveTrueAndStatus(...,APPROVED) is false.
        String inactiveSlug = "PENDING_CATEGORY";
        when(platformCategoryRepository.existsByNameAndActiveTrueAndStatus(
                eq(inactiveSlug), eq(PlatformCategoryStatus.APPROVED))).thenReturn(false);
        var request = new SuggestServiceTypeRequest("Послуга", inactiveSlug, null);

        assertThatThrownBy(() -> service.suggestServiceType(request, userId))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .as("inactive/PENDING slug must surface as 400 BAD_REQUEST")
                        .isEqualTo(HttpStatus.BAD_REQUEST));

        verifyNoInteractions(serviceTypeSuggestionService);
        // The APPROVED+active gate was the rejection point.
        verify(platformCategoryRepository).existsByNameAndActiveTrueAndStatus(
                inactiveSlug, PlatformCategoryStatus.APPROVED);
    }

    @Test
    @DisplayName("suggestServiceType — forwards the resolved category slug (not a UUID) to the suggestion service")
    void should_includeSlugNotUuidInBody_when_suggestServiceTypeCalled() {
        UUID userId = UUID.randomUUID();
        String categoryName = "HAIRDRESSING";
        when(platformCategoryRepository.existsByNameAndActiveTrueAndStatus(
                eq(categoryName), eq(PlatformCategoryStatus.APPROVED))).thenReturn(true);
        var request = new SuggestServiceTypeRequest("Стрижка модельна", categoryName, "Опис");

        service.suggestServiceType(request, userId);

        // The admin email body is composed inside ServiceTypeSuggestionService (Phase 16.8);
        // at this layer the contract is that the System-B slug — not a System-A UUID — is the
        // category argument delegated to submitSuggestion.
        var categoryCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(serviceTypeSuggestionService).submitSuggestion(
                categoryCaptor.capture(), eq("Стрижка модельна"), eq("Опис"), eq(userId));
        String delegatedCategory = categoryCaptor.getValue();
        assertThat(delegatedCategory)
                .as("the resolved System-B slug must be forwarded, actual=%s", delegatedCategory)
                .isEqualTo("HAIRDRESSING");
        // Regression guard: the old contract interpolated a raw System-A UUID. The delegated
        // category must not be a UUID-shaped token (8-4-4-4-12 hex).
        assertThat(delegatedCategory)
                .as("category arg must not regress to a UUID, actual=%s", delegatedCategory)
                .doesNotMatch("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    }

    @Test
    @DisplayName("suggestServiceType — validates the slug BEFORE delegating to the suggestion service (gate precedes dispatch)")
    void should_validateSlugBeforeEmail_when_suggestServiceTypeCalled() {
        UUID userId = UUID.randomUUID();
        String categoryName = "NAIL_SERVICE";
        when(platformCategoryRepository.existsByNameAndActiveTrueAndStatus(
                eq(categoryName), eq(PlatformCategoryStatus.APPROVED))).thenReturn(true);
        var request = new SuggestServiceTypeRequest("Манікюр", categoryName, null);

        var inOrder = org.mockito.Mockito.inOrder(platformCategoryRepository, serviceTypeSuggestionService);
        service.suggestServiceType(request, userId);

        inOrder.verify(platformCategoryRepository).existsByNameAndActiveTrueAndStatus(
                categoryName, PlatformCategoryStatus.APPROVED);
        inOrder.verify(serviceTypeSuggestionService).submitSuggestion(
                categoryName, "Манікюр", null, userId);
    }

    @Test
    @DisplayName("falls back to findAllActiveWithCategory when q is an empty string")
    void should_fallbackToAllActive_when_qIsEmptyString() {
        when(serviceTypeLookup.getByCategory(null)).thenReturn(List.of());

        service.searchServiceTypes(null, "");

        verify(serviceTypeLookup).getByCategory(null);
    }

    @Test
    @DisplayName("should not check category existence when categoryId is null")
    void should_notCheckCategoryExistence_when_categoryIdIsNull() {
        when(serviceTypeLookup.getByCategory(null)).thenReturn(List.of());

        service.searchServiceTypes(null, null);

        verify(catalogCategoryLookup, never()).getAll();
    }

    @Test
    @DisplayName("should throw NotFoundException when categoryId does not exist")
    void should_throwNotFoundException_when_categoryIdDoesNotExist() {
        UUID unknownCategoryId = UUID.randomUUID();
        when(catalogCategoryLookup.getAll()).thenReturn(List.of());

        assertThatThrownBy(() -> service.searchServiceTypes(unknownCategoryId, null))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Category not found");
    }

    // ── findServiceTypesByPlatformCategory (Phase 16.2) ───────────────────────

    @Test
    @DisplayName("maps ServiceType to PlatformServiceTypeResponse with nameUk and categoryName populated")
    void should_mapNameUkAndCategoryName_when_findByPlatformCategory() {
        UUID typeId = UUID.randomUUID();
        var type = mock(ServiceType.class);
        when(type.getId()).thenReturn(typeId);
        when(type.getSlug()).thenReturn("lash-classic");
        when(type.getNameUk()).thenReturn("Нарощування вій (класика)");
        when(type.getPlatformCategoryName()).thenReturn("EYELASH");

        when(serviceTypeRepository.findActiveByPlatformCategoryName("EYELASH"))
                .thenReturn(List.of(type));

        List<PlatformServiceTypeResponse> result =
                service.findServiceTypesByPlatformCategory("EYELASH");

        assertThat(result)
                .extracting(
                        PlatformServiceTypeResponse::id,
                        PlatformServiceTypeResponse::slug,
                        PlatformServiceTypeResponse::nameUk,
                        PlatformServiceTypeResponse::categoryName)
                .containsExactly(tuple(typeId, "lash-classic", "Нарощування вій (класика)", "EYELASH"));
        verify(serviceTypeRepository).findActiveByPlatformCategoryName("EYELASH");
    }

    @Test
    @DisplayName("returns empty list and never hits the repository when categoryName is null")
    void should_returnEmptyAndSkipRepo_when_categoryNameIsNull() {
        List<PlatformServiceTypeResponse> result =
                service.findServiceTypesByPlatformCategory(null);

        assertThat(result).isEmpty();
        verify(serviceTypeRepository, never()).findActiveByPlatformCategoryName(any());
    }

    @Test
    @DisplayName("returns empty list and never hits the repository when categoryName is blank/whitespace")
    void should_returnEmptyAndSkipRepo_when_categoryNameIsBlank() {
        List<PlatformServiceTypeResponse> result =
                service.findServiceTypesByPlatformCategory("   ");

        assertThat(result).isEmpty();
        verify(serviceTypeRepository, never()).findActiveByPlatformCategoryName(any());
    }

    @Test
    @DisplayName("returns empty list (not 404) when the repository finds no rows for a known-shaped slug")
    void should_returnEmptyList_when_repositoryReturnsNoRows() {
        when(serviceTypeRepository.findActiveByPlatformCategoryName("NOPE"))
                .thenReturn(List.of());

        List<PlatformServiceTypeResponse> result =
                service.findServiceTypesByPlatformCategory("NOPE");

        assertThat(result).isEmpty();
        verify(serviceTypeRepository).findActiveByPlatformCategoryName("NOPE");
    }
}
