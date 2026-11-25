package com.beautica.catalog.admin.dto;

import java.util.UUID;

/**
 * B504 — one row of {@code GET /api/v1/internal/catalog/categories/{name}/service-types}: every type, hidden included.
 *
 * @param slug       immutable (deep links and search keys use it)
 * @param nameEn     may be {@code null}
 * @param usageCount service definitions with this type, active and inactive
 */
public record CatalogServiceTypeAdminDto(
        UUID id,
        String slug,
        String nameUk,
        String nameEn,
        boolean active,
        long usageCount
) {}
