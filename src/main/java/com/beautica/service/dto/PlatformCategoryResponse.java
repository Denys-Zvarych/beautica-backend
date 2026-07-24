package com.beautica.service.dto;

import com.beautica.service.entity.PlatformCategory;

/**
 * Response DTO for a newly created {@link PlatformCategory}.
 */
public record PlatformCategoryResponse(
        Long id,
        String name,
        boolean active
) {
    public static PlatformCategoryResponse from(PlatformCategory category) {
        return new PlatformCategoryResponse(
                category.getId(),
                category.getName(),
                category.isActive()
        );
    }
}
