package com.beautica.catalog.admin.dto;

import com.beautica.service.entity.PlatformCategoryStatus;
import org.springframework.lang.Nullable;

/**
 * B504 — one row of {@code GET /api/v1/internal/catalog/categories}: every category in any status.
 *
 * @param name        the immutable wire name (the path key of {@code PATCH /categories/{name}})
 * @param displayName the editable label
 * @param active      {@code false} = hidden from new use (only meaningful on an APPROVED row)
 * @param usageCount  service definitions with this category, active and inactive
 * @param typeCount   service types in this category, active and hidden
 * @param iconUrl     B539 — public URL of the category icon; {@code null} when none is set yet
 */
public record CatalogCategoryAdminDto(
        String name,
        String displayName,
        PlatformCategoryStatus status,
        boolean active,
        long usageCount,
        long typeCount,
        @Nullable String iconUrl
) {}
