package com.beautica.service.dto;

import com.beautica.service.repository.PlatformCategoryRepository.CategoryUsageProjection;

/**
 * Response DTO for listing platform categories with their usage counts.
 */
public record PlatformCategoryUsageResponse(
        String name,
        boolean active,
        long usageCount
) {
    public static PlatformCategoryUsageResponse from(CategoryUsageProjection projection) {
        return new PlatformCategoryUsageResponse(
                projection.getName(),
                projection.isActive(),
                projection.getUsageCount()
        );
    }
}
