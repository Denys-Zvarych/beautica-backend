package com.beautica.service.dto;

import com.beautica.service.entity.ServiceType;

import java.util.UUID;

/**
 * Public response DTO for the platform-category-keyed service-type lookup.
 *
 * <p>Deliberately separate from {@link ServiceTypeResponse} (which carries
 * {@code categoryId} and {@code nameEn} for the legacy catalog search endpoint)
 * so neither shape is polluted to satisfy the other caller's contract.
 *
 * <p>Used by {@code GET /api/v1/service-types?categoryName={slug}} — a
 * {@code permitAll()} endpoint. {@code ownerId} and other internal IDs are
 * intentionally absent (anti-bug §I).
 */
public record PlatformServiceTypeResponse(
        UUID id,
        String slug,
        String nameUk,
        String categoryName
) {

    public static PlatformServiceTypeResponse from(ServiceType t) {
        return new PlatformServiceTypeResponse(
                t.getId(),
                t.getSlug(),
                t.getNameUk(),
                t.getPlatformCategoryName()
        );
    }
}
