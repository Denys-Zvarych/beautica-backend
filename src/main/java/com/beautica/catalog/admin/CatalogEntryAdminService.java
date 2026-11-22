package com.beautica.catalog.admin;

import com.beautica.catalog.CatalogCacheEvictor;
import com.beautica.catalog.admin.dto.CatalogCategoryAdminDto;
import com.beautica.catalog.admin.dto.CatalogServiceTypeAdminDto;
import com.beautica.catalog.admin.dto.UpdateCatalogCategoryRequest;
import com.beautica.catalog.admin.dto.UpdateCatalogServiceTypeRequest;
import com.beautica.catalog.admin.repository.CatalogEntryQueryRepository;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.service.service.PlatformCatalogEditService;
import com.beautica.service.service.PlatformCatalogEditService.CategoryEdit;
import com.beautica.service.service.PlatformCatalogEditService.ServiceTypeEdit;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * B504 (SVC-56) — the catalog portal's view of the LIVE catalog: list categories / types with usage counts, and
 * rename / hide / unhide either.
 *
 * <p>The domain change is delegated to {@link PlatformCatalogEditService} (joins this transaction). This class records
 * one audit row per changed aspect in the SAME transaction — {@code RENAME} (the name fields only), then {@code HIDE}
 * or {@code UNHIDE} ({@code active} only) — and, when anything changed, calls {@link CatalogCacheEvictor#evictAll()},
 * which runs after commit (a rollback evicts nothing). A PATCH whose values equal the current ones writes nothing.
 */
@Service
@RequiredArgsConstructor
public class CatalogEntryAdminService {

    static final String RENAME_ACTION = "RENAME";
    static final String HIDE_ACTION = "HIDE";
    static final String UNHIDE_ACTION = "UNHIDE";
    private static final String ACTIVE = "active";

    /** Same rule as {@code chk_platform_categories_name}; anything else cannot be a wire name. */
    private static final Pattern WIRE_NAME = Pattern.compile("^[A-Z][A-Z0-9_]{0,99}$");

    private final CatalogEntryQueryRepository queryRepository;
    private final PlatformCatalogEditService editService;
    private final CatalogAdminAuditService auditService;
    private final CatalogCacheEvictor cacheEvictor;

    @Transactional(readOnly = true)
    public List<CatalogCategoryAdminDto> listCategories() {
        return queryRepository.findCategories();
    }

    /** 400 for a malformed wire name, 404 for an unknown one. */
    @Transactional(readOnly = true)
    public List<CatalogServiceTypeAdminDto> listServiceTypes(String categoryName) {
        requireWireName(categoryName);
        if (!queryRepository.categoryExists(categoryName)) {
            throw new NotFoundException("Category not found");
        }
        return queryRepository.findServiceTypes(categoryName);
    }

    /** Renames (display name only) and / or hides / unhides a live category, keyed by its wire name. */
    @Transactional
    public void updateCategory(AdminActor actor, String categoryName, UpdateCatalogCategoryRequest request) {
        requireWireName(categoryName);
        CategoryEdit edit = editService.editCategory(categoryName, request.displayName(), request.active());
        if (edit.renamed()) {
            auditService.record(actor, RENAME_ACTION, CatalogAuditTargetType.CATEGORY, categoryName,
                    Map.of("displayName", edit.displayNameBefore()), Map.of("displayName", edit.displayNameAfter()));
        }
        if (edit.visibilityChanged()) {
            recordVisibility(actor, CatalogAuditTargetType.CATEGORY, categoryName, edit.activeBefore(), edit.activeAfter());
        }
        if (edit.renamed() || edit.visibilityChanged()) {
            cacheEvictor.evictAll();
        }
    }

    /** Renames ({@code nameUk} / {@code nameEn}; slug unchanged) and / or hides / unhides a service type. */
    @Transactional
    public void updateServiceType(AdminActor actor, UUID id, UpdateCatalogServiceTypeRequest request) {
        ServiceTypeEdit edit = editService.editServiceType(id, request.nameUk(), request.nameEn(), request.active());
        String targetId = id.toString();
        if (edit.renamed()) {
            auditService.record(actor, RENAME_ACTION, CatalogAuditTargetType.SERVICE_TYPE, targetId,
                    typeNames(edit.nameUkBefore(), edit.nameEnBefore()), typeNames(edit.nameUkAfter(), edit.nameEnAfter()));
        }
        if (edit.visibilityChanged()) {
            recordVisibility(actor, CatalogAuditTargetType.SERVICE_TYPE, targetId, edit.activeBefore(), edit.activeAfter());
        }
        if (edit.renamed() || edit.visibilityChanged()) {
            cacheEvictor.evictAll();
        }
    }

    private void recordVisibility(AdminActor actor, CatalogAuditTargetType targetType, String targetId,
                                  boolean before, boolean after) {
        auditService.record(actor, after ? UNHIDE_ACTION : HIDE_ACTION, targetType, targetId,
                Map.of(ACTIVE, before), Map.of(ACTIVE, after));
    }

    /** {@link LinkedHashMap}, not {@code Map.of}: {@code nameEn} may be {@code null}. */
    private static Map<String, Object> typeNames(String nameUk, String nameEn) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("nameUk", nameUk);
        fields.put("nameEn", nameEn);
        return fields;
    }

    private static void requireWireName(String categoryName) {
        if (categoryName == null || !WIRE_NAME.matcher(categoryName).matches()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Invalid category name");
        }
    }
}
