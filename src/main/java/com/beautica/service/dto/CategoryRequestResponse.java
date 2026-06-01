package com.beautica.service.dto;

import com.beautica.service.entity.PlatformCategory;

/**
 * Response for a freshly submitted category request (201). Deliberately minimal —
 * NEVER includes the token, the requester id, or any internal column.
 */
public record CategoryRequestResponse(
        String name,
        String displayName,
        String status
) {
    public static CategoryRequestResponse from(PlatformCategory category) {
        return new CategoryRequestResponse(
                category.getName(),
                category.getDisplayName(),
                category.getStatus().name()
        );
    }
}
