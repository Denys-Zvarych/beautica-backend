package com.beautica.service.service;

import com.beautica.master.repository.MasterRepository;
import com.beautica.notification.EmailService;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.service.dto.CatalogCategoryResponse;
import com.beautica.service.dto.ServiceTypeResponse;
import com.beautica.service.dto.SuggestServiceTypeRequest;
import com.beautica.service.entity.CatalogCategory;
import com.beautica.service.entity.ServiceType;
import com.beautica.service.repository.CatalogCategoryRepository;
import com.beautica.service.repository.MasterServiceRepository;
import com.beautica.service.repository.ServiceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ServiceCatalogService — catalog methods unit")
class ServiceCatalogServiceCatalogTest {

    private static final String ADMIN_EMAIL = "admin@beautica.test";

    @Mock private ServiceRepository serviceRepository;
    @Mock private MasterServiceRepository masterServiceRepository;
    @Mock private SalonRepository salonRepository;
    @Mock private MasterRepository masterRepository;
    @Mock private CatalogCategoryRepository catalogCategoryRepository;
    @Mock private EmailService emailService;
    @Mock private ServiceTypeLookup serviceTypeLookup;
    @Mock private ServiceTypeSearchService serviceTypeSearchService;

    private ServiceCatalogService service;

    @BeforeEach
    void setUp() {
        service = new ServiceCatalogService(
                serviceRepository,
                masterServiceRepository,
                salonRepository,
                masterRepository,
                catalogCategoryRepository,
                emailService,
                serviceTypeLookup,
                serviceTypeSearchService
        );
        ReflectionTestUtils.setField(service, "adminEmail", ADMIN_EMAIL);
    }

    // ── getCategories ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("returns categories ordered by sortOrder from repository")
    void should_returnCategories_when_repositoryReturnsSortedList() {
        var cat1 = CatalogCategory.builder().nameUk("Нігті").nameEn("Nails").sortOrder(1).build();
        var cat2 = CatalogCategory.builder().nameUk("Брови").nameEn("Brows").sortOrder(2).build();

        when(catalogCategoryRepository.findAllByOrderBySortOrderAsc()).thenReturn(List.of(cat1, cat2));

        List<CatalogCategoryResponse> result = service.getCategories();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).nameUk()).isEqualTo("Нігті");
        assertThat(result.get(1).nameUk()).isEqualTo("Брови");
    }

    @Test
    @DisplayName("returns empty list when no categories exist")
    void should_returnEmptyList_when_noCategoriesExist() {
        when(catalogCategoryRepository.findAllByOrderBySortOrderAsc()).thenReturn(List.of());

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

        when(serviceTypeLookup.getByCategory(categoryId)).thenReturn(List.of(type));

        List<ServiceTypeResponse> result = service.searchServiceTypes(categoryId, null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).categoryId()).isEqualTo(categoryId);
        verify(serviceTypeLookup).getByCategory(categoryId);
    }

    // ── searchServiceTypes — with q ≥ 3 chars ────────────────────────────────

    @Test
    @DisplayName("delegates to searchByName when q has 3 or more characters")
    void should_delegateToSearchByName_when_qHasTwoOrMoreChars() {
        UUID categoryId = UUID.randomUUID();
        var expected = new ServiceTypeResponse(UUID.randomUUID(), categoryId, "Манікюр класичний", "Classic Manicure", "manicure-classic");

        when(serviceTypeSearchService.searchByName("Ман", null)).thenReturn(List.of(expected));

        List<ServiceTypeResponse> result = service.searchServiceTypes(null, "Ман");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).nameUk()).isEqualTo("Манікюр класичний");
        verify(serviceTypeSearchService).searchByName("Ман", null);
    }

    @Test
    @DisplayName("strips whitespace from q before delegating to search service")
    void should_stripWhitespace_when_qHasLeadingOrTrailingSpaces() {
        when(serviceTypeSearchService.searchByName("Гель", null)).thenReturn(List.of());

        service.searchServiceTypes(null, "  Гель  ");

        verify(serviceTypeSearchService).searchByName("Гель", null);
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

        when(serviceTypeSearchService.searchByName("Ман", categoryId)).thenReturn(List.of(expected));

        List<ServiceTypeResponse> result = service.searchServiceTypes(categoryId, "Ман");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).categoryId()).isEqualTo(categoryId);
        // serviceTypeSearchService.searchByName receives the categoryId so the DB query is category-scoped
        verify(serviceTypeSearchService).searchByName("Ман", categoryId);
        verify(serviceTypeSearchService, never()).searchByName(eq("Ман"), eq(null));
    }

    @Test
    @DisplayName("uses plain searchByName (categoryId=null) when categoryId is null and q >= 3 chars")
    void should_useSearchByName_when_categoryIdIsNull() {
        when(serviceTypeSearchService.searchByName("Ман", null)).thenReturn(List.of());

        service.searchServiceTypes(null, "Ман");

        verify(serviceTypeSearchService).searchByName("Ман", null);
        // searchByName with a non-null categoryId must never be called
        verify(serviceTypeSearchService, never()).searchByName(anyString(), any(UUID.class));
    }

    // ── suggestServiceType ────────────────────────────────────────────────────

    @Test
    @DisplayName("sends admin notification email with correct subject and body")
    void should_sendAdminNotification_when_suggestServiceTypeCalled() {
        UUID userId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        var request = new SuggestServiceTypeRequest("Ламінування вій", categoryId, "Детальний опис");

        service.suggestServiceType(request, userId);

        verify(emailService).sendAdminNotification(
                eq(ADMIN_EMAIL),
                anyString(),
                anyString()
        );
    }

    @Test
    @DisplayName("includes service name in the email subject")
    void should_includeNameInSubject_when_suggestServiceTypeCalled() {
        UUID userId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        var request = new SuggestServiceTypeRequest("Ламінування вій", categoryId, null);

        service.suggestServiceType(request, userId);

        var subjectCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(emailService).sendAdminNotification(
                eq(ADMIN_EMAIL),
                subjectCaptor.capture(),
                anyString()
        );
        assertThat(subjectCaptor.getValue()).contains("Ламінування вій");
    }

    @Test
    @DisplayName("uses em dash placeholder in body when description is null")
    void should_usePlaceholder_when_descriptionIsNull() {
        UUID userId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        var request = new SuggestServiceTypeRequest("Ламінування вій", categoryId, null);

        service.suggestServiceType(request, userId);

        var bodyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(emailService).sendAdminNotification(
                eq(ADMIN_EMAIL),
                anyString(),
                bodyCaptor.capture()
        );
        assertThat(bodyCaptor.getValue()).contains("—");
    }

    @Test
    @DisplayName("body passed to sendAdminNotification includes the requesting userId")
    void should_includeUserIdInBody_when_suggestServiceTypeCalled() {
        UUID userId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        var request = new SuggestServiceTypeRequest("Ламінування вій", categoryId, "Опис");

        service.suggestServiceType(request, userId);

        var bodyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(emailService).sendAdminNotification(
                eq(ADMIN_EMAIL),
                anyString(),
                bodyCaptor.capture()
        );
        assertThat(bodyCaptor.getValue())
                .as("email body must contain the userId of the requester")
                .contains(userId.toString());
    }

    @Test
    @DisplayName("falls back to findAllActiveWithCategory when q is an empty string")
    void should_fallbackToAllActive_when_qIsEmptyString() {
        when(serviceTypeLookup.getByCategory(null)).thenReturn(List.of());

        service.searchServiceTypes(null, "");

        verify(serviceTypeLookup).getByCategory(null);
    }
}
