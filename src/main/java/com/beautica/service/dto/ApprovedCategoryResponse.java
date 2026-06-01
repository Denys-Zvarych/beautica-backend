package com.beautica.service.dto;

import com.beautica.service.entity.PlatformCategory;

/**
 * Approved-category picker item returned by
 * {@code GET /api/v1/service-categories/approved} (authenticated). Carries only the
 * wire {@code name} and the human-readable {@code displayName} — no id, no requester,
 * no internal columns.
 */
public record ApprovedCategoryResponse(
        String name,
        String displayName
) {
    public static ApprovedCategoryResponse from(PlatformCategory category) {
        return new ApprovedCategoryResponse(category.getName(), category.getDisplayName());
    }
}
