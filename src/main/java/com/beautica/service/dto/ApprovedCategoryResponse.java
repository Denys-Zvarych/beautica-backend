package com.beautica.service.dto;

import com.beautica.service.entity.PlatformCategory;
import org.springframework.lang.Nullable;

import java.util.function.Function;

/**
 * Approved-category picker item returned by
 * {@code GET /api/v1/service-categories/approved} (public, {@code permitAll} since W050). Carries only the
 * wire {@code name}, the human-readable {@code displayName} and (B539, SVC-58) the icon's public
 * {@code iconUrl} — no id, no requester, no internal columns.
 *
 * @param iconUrl public URL of the category's canonical SVG icon (content-addressed, immutable);
 *                {@code null} when the category has no icon yet (the app falls back to its bundled set)
 */
public record ApprovedCategoryResponse(
        String name,
        String displayName,
        @Nullable String iconUrl
) {
    /** A category without an icon. */
    public ApprovedCategoryResponse(String name, String displayName) {
        this(name, displayName, null);
    }

    /** @param iconUrlForKey builds the public URL from the stored icon key ({@code null} key → {@code null}) */
    public static ApprovedCategoryResponse from(PlatformCategory category, Function<String, String> iconUrlForKey) {
        return new ApprovedCategoryResponse(category.getName(), category.getDisplayName(),
                iconUrlForKey.apply(category.getIconKey()));
    }
}
