package com.beautica.catalog.admin.controller;

import com.beautica.catalog.admin.AdminActor;
import com.beautica.catalog.admin.CatalogEntryAdminService;
import com.beautica.catalog.admin.dto.CatalogCategoryAdminDto;
import com.beautica.catalog.admin.dto.CatalogServiceTypeAdminDto;
import com.beautica.catalog.admin.dto.UpdateCatalogCategoryRequest;
import com.beautica.catalog.admin.dto.UpdateCatalogServiceTypeRequest;
import com.beautica.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * B504 (SVC-56) — the catalog portal's live-catalog page: list, rename, hide and unhide categories and service types.
 * Under {@code /api/v1/internal/catalog/} so {@code InternalApiKeyFilter} admits the master key or the catalog-only
 * key (SVC-54); no JWT. Same behaviour on every profile.
 *
 * <p>Categories are keyed by their immutable wire {@code name} (the same {@code /categories/{name}} shape the icon
 * upload {@code PUT /categories/{name}/icon} extends, B539); types by id.
 *
 * <p>Errors: a refused write is a 409 with the stable code under {@code data.code} ({@code TYPE_EXISTS},
 * {@code CATEGORY_NAME_EXISTS}, {@code CATEGORY_NOT_APPROVED}); an empty body, a bad name, a malformed id or wire name
 * and a missing / invalid {@value AdminActor#HEADER} are a 400; an unknown category or type is a 404.
 */
@RestController
@RequestMapping("/api/v1/internal/catalog")
@RequiredArgsConstructor
public class InternalCatalogController {

    private final CatalogEntryAdminService adminService;

    /** Every category in any status, with {@code active}, the service usage count and the type count. */
    @GetMapping("/categories")
    public ApiResponse<List<CatalogCategoryAdminDto>> listCategories() {
        return ApiResponse.ok(adminService.listCategories());
    }

    /** Every type of the category (hidden included), with {@code active} and the service usage count. */
    @GetMapping("/categories/{name}/service-types")
    public ApiResponse<List<CatalogServiceTypeAdminDto>> listServiceTypes(@PathVariable String name) {
        return ApiResponse.ok(adminService.listServiceTypes(name));
    }

    /**
     * {@code {displayName?, active?}} — renames the display name and / or hides / unhides. 200 with {@code data: null}.
     * The actor header is read as optional so a missing one is {@link AdminActor}'s 400, not a binding error.
     */
    @PatchMapping("/categories/{name}")
    public ApiResponse<Void> updateCategory(
            @RequestHeader(value = AdminActor.HEADER, required = false) String actorHeader,
            @PathVariable String name,
            @Valid @RequestBody UpdateCatalogCategoryRequest request) {
        adminService.updateCategory(AdminActor.of(actorHeader), name, request);
        return ApiResponse.ok(null);
    }

    /** {@code {nameUk?, nameEn?, active?}} — renames and / or hides / unhides; slug unchanged. 200, {@code data: null}. */
    @PatchMapping("/service-types/{id}")
    public ApiResponse<Void> updateServiceType(
            @RequestHeader(value = AdminActor.HEADER, required = false) String actorHeader,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateCatalogServiceTypeRequest request) {
        adminService.updateServiceType(AdminActor.of(actorHeader), id, request);
        return ApiResponse.ok(null);
    }
}
