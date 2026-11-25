package com.beautica.catalog.admin;

import com.beautica.catalog.CatalogCacheEvictor;
import com.beautica.catalog.admin.dto.CatalogServiceTypeAdminDto;
import com.beautica.catalog.admin.dto.UpdateCatalogCategoryRequest;
import com.beautica.catalog.admin.dto.UpdateCatalogServiceTypeRequest;
import com.beautica.catalog.admin.repository.CatalogEntryQueryRepository;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.CatalogRequestConflictException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.service.service.PlatformCatalogEditService;
import com.beautica.common.exception.CatalogUsage;
import com.beautica.service.entity.PlatformCategoryStatus;
import com.beautica.service.service.PlatformCatalogDeleteService;
import com.beautica.service.service.PlatformCatalogDeleteService.CategoryDeletion;
import com.beautica.service.service.PlatformCatalogDeleteService.ServiceTypeDeletion;
import com.beautica.service.service.PlatformCatalogEditService.CategoryEdit;
import com.beautica.service.service.PlatformCatalogEditService.ServiceTypeEdit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CatalogEntryAdminService — B504 unit")
class CatalogEntryAdminServiceTest {

    private static final AdminActor ACTOR = AdminActor.of("beautica.ua@gmail.com");
    private static final String CATEGORY = "MASAZH_NIH";

    @Mock private CatalogEntryQueryRepository queryRepository;
    @Mock private PlatformCatalogEditService editService;
    @Mock private PlatformCatalogDeleteService deleteService;
    @Mock private CatalogAdminAuditService auditService;
    @Mock private CatalogCacheEvictor cacheEvictor;

    private CatalogEntryAdminService service;

    @BeforeEach
    void setUp() {
        service = new CatalogEntryAdminService(queryRepository, editService, deleteService, auditService, cacheEvictor);
    }

    // ── lists ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("type list of a known category comes back unchanged from the query repository")
    void should_returnTypes_when_categoryExists() {
        var row = new CatalogServiceTypeAdminDto(UUID.randomUUID(), "masazh", "Масаж", null, false, 2);
        when(queryRepository.categoryExists(CATEGORY)).thenReturn(true);
        when(queryRepository.findServiceTypes(CATEGORY)).thenReturn(List.of(row));

        List<CatalogServiceTypeAdminDto> types = service.listServiceTypes(CATEGORY);

        assertThat(types).containsExactly(row);
    }

    @Test
    @DisplayName("type list of an unknown wire name is a 404")
    void should_throwNotFound_when_listingTypesOfUnknownCategory() {
        when(queryRepository.categoryExists(CATEGORY)).thenReturn(false);

        Throwable thrown = catchThrowable(() -> service.listServiceTypes(CATEGORY));

        assertThat(thrown).isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("a malformed wire name is a 400 before any query runs")
    void should_throwBadRequest_when_wireNameMalformed() {
        Throwable thrown = catchThrowable(() -> service.listServiceTypes("masazh nih"));

        assertThat(thrown).isInstanceOf(BusinessException.class).hasMessage("Invalid category name");
        verifyNoInteractions(queryRepository);
    }

    // ── category writes ────────────────────────────────────────────────────────

    @Test
    @DisplayName("rename + hide in one PATCH writes RENAME then HIDE audit rows, then evicts once")
    void should_auditRenameThenHideAndEvict_when_bothChange() {
        when(editService.editCategory(CATEGORY, "Масаж стоп", false))
                .thenReturn(new CategoryEdit(CATEGORY, "Масаж ніг", "Масаж стоп", true, false));

        service.updateCategory(ACTOR, CATEGORY, new UpdateCatalogCategoryRequest("Масаж стоп", false));

        InOrder order = inOrder(auditService, cacheEvictor);
        order.verify(auditService).record(ACTOR, "RENAME", CatalogAuditTargetType.CATEGORY, CATEGORY,
                Map.of("displayName", "Масаж ніг"), Map.of("displayName", "Масаж стоп"));
        order.verify(auditService).record(ACTOR, "HIDE", CatalogAuditTargetType.CATEGORY, CATEGORY,
                Map.of("active", true), Map.of("active", false));
        order.verify(cacheEvictor).evictAll();
    }

    @Test
    @DisplayName("unhide alone writes exactly one UNHIDE audit row and evicts")
    void should_auditUnhideOnly_when_categoryUnhidden() {
        when(editService.editCategory(CATEGORY, null, true))
                .thenReturn(new CategoryEdit(CATEGORY, "Масаж ніг", "Масаж ніг", false, true));

        service.updateCategory(ACTOR, CATEGORY, new UpdateCatalogCategoryRequest(null, true));

        verify(auditService).record(ACTOR, "UNHIDE", CatalogAuditTargetType.CATEGORY, CATEGORY,
                Map.of("active", false), Map.of("active", true));
        verify(auditService, times(1)).record(any(), anyString(), any(), anyString(), any(), any());
        verify(cacheEvictor).evictAll();
    }

    @Test
    @DisplayName("a category PATCH equal to the current values writes no audit row and evicts nothing")
    void should_writeNothing_when_categoryEditChangesNothing() {
        when(editService.editCategory(CATEGORY, "Масаж ніг", true))
                .thenReturn(new CategoryEdit(CATEGORY, "Масаж ніг", "Масаж ніг", true, true));

        service.updateCategory(ACTOR, CATEGORY, new UpdateCatalogCategoryRequest("Масаж ніг", true));

        verifyNoInteractions(auditService, cacheEvictor);
    }

    @Test
    @DisplayName("a refused category edit (409) propagates with no audit row and no eviction")
    void should_propagateConflictWithoutAuditOrEviction_when_editRefused() {
        when(editService.editCategory(CATEGORY, "Манікюр", null)).thenThrow(
                new CatalogRequestConflictException(CatalogRequestConflictException.Code.CATEGORY_NAME_EXISTS));

        Throwable thrown = catchThrowable(
                () -> service.updateCategory(ACTOR, CATEGORY, new UpdateCatalogCategoryRequest("Манікюр", null)));

        assertThat(thrown).isInstanceOf(CatalogRequestConflictException.class);
        verifyNoInteractions(auditService, cacheEvictor);
    }

    @Test
    @DisplayName("a malformed category path is a 400 before the edit, audit or eviction runs")
    void should_throwBadRequestBeforeEdit_when_categoryPathMalformed() {
        Throwable thrown = catchThrowable(
                () -> service.updateCategory(ACTOR, "../x", new UpdateCatalogCategoryRequest("Нова", null)));

        assertThat(thrown).isInstanceOf(BusinessException.class).hasMessage("Invalid category name");
        verifyNoInteractions(editService, auditService, cacheEvictor);
    }

    // ── type writes ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a type rename audits nameUk / nameEn (cleared English name as null) and evicts")
    void should_auditRenameWithNullEnglishNameAndEvict_when_typeRenamed() {
        UUID id = UUID.randomUUID();
        when(editService.editServiceType(id, "Масаж стоп", "", null))
                .thenReturn(new ServiceTypeEdit(id, "Масаж ніг", "Foot", "Масаж стоп", null, true, true));

        service.updateServiceType(ACTOR, id, new UpdateCatalogServiceTypeRequest("Масаж стоп", "", null));

        Map<String, Object> before = new HashMap<>();
        before.put("nameUk", "Масаж ніг");
        before.put("nameEn", "Foot");
        Map<String, Object> after = new HashMap<>();
        after.put("nameUk", "Масаж стоп");
        after.put("nameEn", null);
        verify(auditService).record(ACTOR, "RENAME", CatalogAuditTargetType.SERVICE_TYPE, id.toString(), before, after);
        verify(cacheEvictor).evictAll();
    }

    @Test
    @DisplayName("hiding a type writes one HIDE audit row with active true → false and evicts")
    void should_auditHide_when_typeHidden() {
        UUID id = UUID.randomUUID();
        when(editService.editServiceType(id, null, null, false))
                .thenReturn(new ServiceTypeEdit(id, "Масаж", null, "Масаж", null, true, false));

        service.updateServiceType(ACTOR, id, new UpdateCatalogServiceTypeRequest(null, null, false));

        verify(auditService).record(ACTOR, "HIDE", CatalogAuditTargetType.SERVICE_TYPE, id.toString(),
                Map.of("active", true), Map.of("active", false));
        verify(cacheEvictor).evictAll();
    }

    @Test
    @DisplayName("a type PATCH equal to the current values writes no audit row and evicts nothing")
    void should_writeNothing_when_typeEditChangesNothing() {
        UUID id = UUID.randomUUID();
        when(editService.editServiceType(id, "Масаж", null, true))
                .thenReturn(new ServiceTypeEdit(id, "Масаж", null, "Масаж", null, true, true));

        service.updateServiceType(ACTOR, id, new UpdateCatalogServiceTypeRequest("Масаж", null, true));

        verifyNoInteractions(auditService, cacheEvictor);
    }

    // ── delete (B505) ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("category delete: one DELETE audit row with the catalog fields in before (after null), then evict")
    void should_auditDeleteWithBeforeThenEvict_when_categoryDeleted() {
        UUID bucket = UUID.randomUUID();
        when(deleteService.deleteCategory(CATEGORY)).thenReturn(new CategoryDeletion(CATEGORY, "Масаж ніг",
                PlatformCategoryStatus.APPROVED, false, null, bucket));

        service.deleteCategory(ACTOR, CATEGORY);

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("name", CATEGORY);
        before.put("displayName", "Масаж ніг");
        before.put("status", "APPROVED");
        before.put("active", false);
        before.put("initialServiceName", null);
        before.put("serviceCategoryId", bucket.toString());
        InOrder order = inOrder(auditService, cacheEvictor);
        order.verify(auditService).record(ACTOR, "DELETE", CatalogAuditTargetType.CATEGORY, CATEGORY, before, null);
        order.verify(cacheEvictor).evictPlatformCatalog();
        verify(cacheEvictor, never()).evictAll();
    }

    @Test
    @DisplayName("type delete: one DELETE audit row with id, category, slug, names, active; then evict")
    void should_auditDeleteWithBeforeThenEvict_when_typeDeleted() {
        UUID id = UUID.randomUUID();
        when(deleteService.deleteServiceType(id)).thenReturn(
                new ServiceTypeDeletion(id, CATEGORY, "masazh-nih", "Масаж ніг", "", true));

        service.deleteServiceType(ACTOR, id);

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("id", id.toString());
        before.put("platformCategoryName", CATEGORY);
        before.put("slug", "masazh-nih");
        before.put("nameUk", "Масаж ніг");
        before.put("nameEn", "");
        before.put("active", true);
        InOrder order = inOrder(auditService, cacheEvictor);
        order.verify(auditService).record(ACTOR, "DELETE", CatalogAuditTargetType.SERVICE_TYPE, id.toString(), before,
                null);
        order.verify(cacheEvictor).evictPlatformCatalog();
        verify(cacheEvictor, never()).evictAll();
    }

    @Test
    @DisplayName("a refused delete (in use) propagates the 409 and writes / evicts nothing")
    void should_propagateInUseWithoutAuditOrEviction_when_deleteRefused() {
        UUID id = UUID.randomUUID();
        when(deleteService.deleteServiceType(id)).thenThrow(new CatalogRequestConflictException(
                CatalogRequestConflictException.Code.CATALOG_IN_USE, CatalogUsage.ofServiceType(2)));

        Throwable thrown = catchThrowable(() -> service.deleteServiceType(ACTOR, id));

        assertThat(thrown).isInstanceOf(CatalogRequestConflictException.class);
        assertThat(((CatalogRequestConflictException) thrown).getUsage()).isEqualTo(CatalogUsage.ofServiceType(2));
        verifyNoInteractions(auditService, cacheEvictor);
    }

    @Test
    @DisplayName("a malformed category wire name on delete is a 400 before the domain call")
    void should_throwBadRequestBeforeDelete_when_categoryPathMalformed() {
        Throwable thrown = catchThrowable(() -> service.deleteCategory(ACTOR, "bad name"));

        assertThat(thrown).isInstanceOf(BusinessException.class);
        verifyNoInteractions(deleteService, auditService, cacheEvictor);
    }
}
