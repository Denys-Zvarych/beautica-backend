package com.beautica.catalog.icon.controller;

import com.beautica.catalog.admin.AdminActor;
import com.beautica.catalog.icon.CategoryIconBackfillService;
import com.beautica.catalog.icon.CategoryIconService;
import com.beautica.catalog.icon.dto.CategoryIconBackfillResponse;
import com.beautica.catalog.icon.dto.CategoryIconResponse;
import com.beautica.catalog.icon.dto.SetCategoryIconRequest;
import com.beautica.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * B539 (SVC-58 / SVC-59) — the catalog portal sets or replaces a category's icon. Under
 * {@code /api/v1/internal/catalog/} so {@code InternalApiKeyFilter} admits the master key or the catalog-only key
 * (SVC-54); no JWT. No DELETE: an icon is replaced, never removed (SVC-58).
 *
 * <p>Errors: 400 {@code ICON_INVALID} with {@code data.reason}; 400 for a bad wire name, a missing / invalid
 * {@value AdminActor#HEADER} or a missing / over-long {@code svg}; 404 unknown category; 409 {@code CATEGORY_REJECTED};
 * 503 when media storage is not configured.
 */
@RestController
@RequestMapping("/api/v1/internal/catalog")
@RequiredArgsConstructor
public class InternalCategoryIconController {

    private final CategoryIconService iconService;
    private final CategoryIconBackfillService backfillService;

    /** {@code {svg}} — 200 with {@code {name, iconUrl}}; re-sending the current icon is a 200 no-op. */
    @PutMapping("/categories/{name}/icon")
    public ApiResponse<CategoryIconResponse> setIcon(
            @RequestHeader(value = AdminActor.HEADER, required = false) String actorHeader,
            @PathVariable String name,
            @Valid @RequestBody SetCategoryIconRequest request) {
        return ApiResponse.ok(iconService.setIcon(name, request.svg(), AdminActor.of(actorHeader)));
    }

    /**
     * B540 (SVC-E12) — gives every PENDING / APPROVED category without an icon its bundled icon; idempotent, never
     * overwrites. 200 with {@code {set, skipped, missing}}; 503 when media storage is not configured and anything
     * would be set.
     */
    @PostMapping("/category-icons/backfill")
    public ApiResponse<CategoryIconBackfillResponse> backfill(
            @RequestHeader(value = AdminActor.HEADER, required = false) String actorHeader) {
        return ApiResponse.ok(backfillService.backfill(AdminActor.of(actorHeader)));
    }
}
