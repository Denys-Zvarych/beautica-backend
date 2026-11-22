package com.beautica.catalog.admin.controller;

import com.beautica.catalog.admin.AdminActor;
import com.beautica.catalog.admin.CatalogRequestAdminService;
import com.beautica.catalog.admin.CatalogRequestKind;
import com.beautica.catalog.admin.CatalogRequestStatus;
import com.beautica.catalog.admin.dto.CatalogRequestDto;
import com.beautica.catalog.admin.dto.EditRequestNameRequest;
import com.beautica.common.ApiResponse;
import com.beautica.common.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * B502 (SVC-55) — the catalog portal's request queue. Under {@code /api/v1/internal/catalog/} (never the bare root),
 * so {@code InternalApiKeyFilter} admits the master key or the catalog-only key (SVC-54); no JWT. Same behaviour on
 * every profile.
 *
 * <p>Errors: a refused edit is a 409 with the stable code under {@code data.code}
 * ({@code REQUEST_NOT_PENDING}, {@code DUPLICATE_PENDING}, {@code TYPE_EXISTS}); a bad name, id, kind or a missing /
 * invalid {@value AdminActor#HEADER} is a 400; an unknown id is a 404.
 */
@RestController
@RequestMapping("/api/v1/internal/catalog/requests")
@RequiredArgsConstructor
public class InternalCatalogRequestController {

    private final CatalogRequestAdminService adminService;

    /** {@code ?status=PENDING} (default) | {@code APPROVED} | {@code REJECTED}; paged, oldest first. */
    @GetMapping
    public ApiResponse<PageResponse<CatalogRequestDto>> list(
            @RequestParam(defaultValue = "PENDING") CatalogRequestStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<CatalogRequestDto> page = adminService.list(status, pageable);
        return ApiResponse.ok(PageResponse.of(
                page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages()));
    }

    /**
     * Corrects the name of a PENDING request: SERVICE_TYPE edits the suggested name, CATEGORY the display name (the
     * wire name never changes) and optionally {@code initialServiceName}. 200 with {@code data: null}.
     * The actor header is read as optional so a missing one is {@link AdminActor}'s 400, not a binding error.
     */
    @PatchMapping("/{kind}/{id}")
    public ApiResponse<Void> editName(
            @RequestHeader(value = AdminActor.HEADER, required = false) String actorHeader,
            @PathVariable CatalogRequestKind kind,
            @PathVariable String id,
            @Valid @RequestBody EditRequestNameRequest request) {
        adminService.editName(AdminActor.of(actorHeader), kind, id, request);
        return ApiResponse.ok(null);
    }
}
