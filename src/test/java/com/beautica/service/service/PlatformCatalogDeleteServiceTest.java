package com.beautica.service.service;

import com.beautica.common.exception.CatalogRequestConflictException;
import com.beautica.common.exception.CatalogUsage;
import com.beautica.common.exception.NotFoundException;
import com.beautica.service.entity.PlatformCategory;
import com.beautica.service.entity.PlatformCategoryStatus;
import com.beautica.service.entity.ServiceType;
import com.beautica.service.repository.PlatformCategoryRepository;
import com.beautica.service.repository.ServiceRepository;
import com.beautica.service.repository.ServiceTypeRepository;
import com.beautica.service.repository.ServiceTypeSuggestionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PlatformCatalogDeleteService — B505 delete of an unused category or type")
class PlatformCatalogDeleteServiceTest {

    private static final String CATEGORY = "MASAZH_NIH";
    private static final long CATEGORY_ID = 42L;

    @Mock private PlatformCategoryRepository platformCategoryRepository;
    @Mock private ServiceTypeRepository serviceTypeRepository;
    @Mock private ServiceRepository serviceRepository;
    @Mock private ServiceTypeSuggestionRepository suggestionRepository;

    @InjectMocks private PlatformCatalogDeleteService service;

    private PlatformCategory approvedCategory(String displayName, boolean active) {
        PlatformCategory category = PlatformCategory.ofApproved(CATEGORY, displayName);
        ReflectionTestUtils.setField(category, "id", CATEGORY_ID);
        category.changeActive(active);
        return category;
    }

    private static ServiceType type(UUID id, String nameUk, String nameEn, boolean active) {
        return ServiceType.builder().id(id).nameUk(nameUk).nameEn(nameEn).slug("masazh-nih")
                .platformCategoryName(CATEGORY).active(active).build();
    }

    private static CatalogRequestConflictException.Code conflictCode(Throwable thrown) {
        assertThat(thrown).isInstanceOf(CatalogRequestConflictException.class);
        return ((CatalogRequestConflictException) thrown).getCode();
    }

    private void categoryUsage(long types, long definitions, long pendingSuggestions) {
        when(serviceTypeRepository.countAllByPlatformCategoryName(CATEGORY)).thenReturn(types);
        when(serviceRepository.countAllByCategory(CATEGORY)).thenReturn(definitions);
        when(suggestionRepository.countPendingByCategoryName(CATEGORY)).thenReturn(pendingSuggestions);
    }

    @Test
    @DisplayName("an unused category is deleted under its row lock (flushed in-method) and its catalog fields returned")
    void should_deleteAndReturnSnapshot_when_categoryUnused() {
        PlatformCategory category = approvedCategory("Масаж ніг", false);
        when(platformCategoryRepository.findByNameForDelete(CATEGORY)).thenReturn(Optional.of(category));
        categoryUsage(0, 0, 0);

        PlatformCatalogDeleteService.CategoryDeletion deleted = service.deleteCategory(CATEGORY);

        verify(platformCategoryRepository).delete(category);
        verify(platformCategoryRepository).flush();
        assertThat(deleted.name()).isEqualTo(CATEGORY);
        assertThat(deleted.displayName()).isEqualTo("Масаж ніг");
        assertThat(deleted.status()).isEqualTo(PlatformCategoryStatus.APPROVED);
        assertThat(deleted.active()).isFalse();
    }

    @Test
    @DisplayName("a category with a type, a definition or a pending suggestion is 409 CATALOG_IN_USE with all three "
            + "counts, and nothing is deleted")
    void should_throwInUseWithCounts_when_categoryReferenced() {
        PlatformCategory category = approvedCategory("Масаж ніг", true);
        when(platformCategoryRepository.findByNameForDelete(CATEGORY)).thenReturn(Optional.of(category));
        categoryUsage(2, 0, 1);

        Throwable thrown = catchThrowable(() -> service.deleteCategory(CATEGORY));

        assertThat(conflictCode(thrown)).isEqualTo(CatalogRequestConflictException.Code.CATALOG_IN_USE);
        assertThat(((CatalogRequestConflictException) thrown).getUsage()).isEqualTo(CatalogUsage.ofCategory(2, 0, 1));
        verify(platformCategoryRepository, never()).delete(any());
    }

    @Test
    @DisplayName("a category used only by a service_definitions.category string (no FK) is still 409")
    void should_throwInUse_when_categoryReferencedOnlyByDefinitionString() {
        PlatformCategory category = approvedCategory("Масаж ніг", true);
        when(platformCategoryRepository.findByNameForDelete(CATEGORY)).thenReturn(Optional.of(category));
        categoryUsage(0, 1, 0);

        Throwable thrown = catchThrowable(() -> service.deleteCategory(CATEGORY));

        assertThat(conflictCode(thrown)).isEqualTo(CatalogRequestConflictException.Code.CATALOG_IN_USE);
        verify(platformCategoryRepository, never()).delete(any());
    }

    @Test
    @DisplayName("deleting a pending / rejected category request is 409 CATEGORY_NOT_APPROVED before any count")
    void should_throwNotApproved_when_deletingCategoryRequest() {
        OffsetDateTime requestedAt = OffsetDateTime.parse("2026-11-01T10:00:00Z");
        PlatformCategory pending = PlatformCategory.ofPendingRequest(CATEGORY, "Масаж ніг", UUID.randomUUID(), "hash",
                requestedAt, requestedAt.plusDays(1), null);
        when(platformCategoryRepository.findByNameForDelete(CATEGORY)).thenReturn(Optional.of(pending));

        Throwable thrown = catchThrowable(() -> service.deleteCategory(CATEGORY));

        assertThat(conflictCode(thrown)).isEqualTo(CatalogRequestConflictException.Code.CATEGORY_NOT_APPROVED);
        verifyNoInteractions(serviceRepository, suggestionRepository);
        verify(platformCategoryRepository, never()).delete(any());
    }

    @Test
    @DisplayName("deleting an unknown category is 404")
    void should_throwNotFound_when_deletingUnknownCategory() {
        when(platformCategoryRepository.findByNameForDelete(CATEGORY)).thenReturn(Optional.empty());

        Throwable thrown = catchThrowable(() -> service.deleteCategory(CATEGORY));

        assertThat(thrown).isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("an unused type is deleted under its row lock and its catalog fields returned")
    void should_deleteAndReturnSnapshot_when_typeUnused() {
        UUID id = UUID.randomUUID();
        ServiceType type = type(id, "Масаж ніг", "", true);
        when(serviceTypeRepository.findByIdForDelete(id)).thenReturn(Optional.of(type));
        when(serviceRepository.countAllByServiceTypeId(id)).thenReturn(0L);

        PlatformCatalogDeleteService.ServiceTypeDeletion deleted = service.deleteServiceType(id);

        verify(serviceTypeRepository).delete(type);
        verify(serviceTypeRepository).flush();
        assertThat(deleted).isEqualTo(new PlatformCatalogDeleteService.ServiceTypeDeletion(
                id, CATEGORY, "masazh-nih", "Масаж ніг", "", true));
    }

    @Test
    @DisplayName("a type used by any definition (inactive included) is 409 CATALOG_IN_USE with {definitions} only")
    void should_throwInUseWithDefinitionCount_when_typeReferenced() {
        UUID id = UUID.randomUUID();
        when(serviceTypeRepository.findByIdForDelete(id)).thenReturn(Optional.of(type(id, "Масаж ніг", "", false)));
        when(serviceRepository.countAllByServiceTypeId(id)).thenReturn(3L);

        Throwable thrown = catchThrowable(() -> service.deleteServiceType(id));

        assertThat(conflictCode(thrown)).isEqualTo(CatalogRequestConflictException.Code.CATALOG_IN_USE);
        assertThat(((CatalogRequestConflictException) thrown).getUsage()).isEqualTo(CatalogUsage.ofServiceType(3));
        verify(serviceTypeRepository, never()).delete(any());
    }

    @Test
    @DisplayName("an FK violation at the in-method flush (the backstop) is the same 409 CATALOG_IN_USE, no counts")
    void should_mapFkViolationToInUse_when_flushFails() {
        UUID id = UUID.randomUUID();
        when(serviceTypeRepository.findByIdForDelete(id)).thenReturn(Optional.of(type(id, "Масаж ніг", "", true)));
        when(serviceRepository.countAllByServiceTypeId(id)).thenReturn(0L);
        doThrow(new DataIntegrityViolationException("fk")).when(serviceTypeRepository).flush();

        Throwable thrown = catchThrowable(() -> service.deleteServiceType(id));

        assertThat(conflictCode(thrown)).isEqualTo(CatalogRequestConflictException.Code.CATALOG_IN_USE);
        assertThat(((CatalogRequestConflictException) thrown).getUsage()).isNull();
    }

    @Test
    @DisplayName("deleting an unknown type is 404")
    void should_throwNotFound_when_deletingUnknownType() {
        UUID id = UUID.randomUUID();
        when(serviceTypeRepository.findByIdForDelete(id)).thenReturn(Optional.empty());

        Throwable thrown = catchThrowable(() -> service.deleteServiceType(id));

        assertThat(thrown).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(serviceRepository);
    }
}
