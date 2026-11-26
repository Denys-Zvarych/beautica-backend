package com.beautica.service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for creating a new {@link com.beautica.service.entity.PlatformCategory}.
 *
 * <p>Validation rules (anti-bug §A):
 * <ul>
 *   <li>{@code @NotBlank} — rejects null and whitespace-only values; the DB NOT NULL
 *       constraint is not a substitute because a blank string passes NOT NULL.</li>
 *   <li>{@code @Size(max = 50)} — caps at 50 chars (well within the DB {@code VARCHAR(100)});
 *       shorter practical limit prevents noise names.</li>
 *   <li>{@code @Pattern} — enforces {@code ^[A-Z][A-Z0-9_]*$}: must start with an
 *       uppercase letter followed by zero or more uppercase letters, digits, or underscores.
 *       {@code @Pattern} alone does not reject null (Bean Validation silently skips null
 *       targets), so {@code @NotBlank} is required to cover that path.</li>
 * </ul>
 */
public record CreatePlatformCategoryRequest(
        @NotBlank(message = "Category name is required")
        @Size(max = 50, message = "Category name must be at most 50 characters")
        @Pattern(
                regexp = "^[A-Z][A-Z0-9_]*$",
                message = "Category name must contain only uppercase letters, digits, or underscores, and start with a letter"
        )
        String name,

        /**
         * B541 (SVC-60) — OPTIONAL icon SVG text, set in the same transaction (needs {@code X-Admin-Actor}); validated
         * by {@code CategoryIconSanitizer} (400 {@code ICON_INVALID}). Required while {@code app.catalog.icon-required}
         * is on (409 {@code CATEGORY_ICON_REQUIRED} without it). Same 24 KiB body cap as the icon PUT.
         */
        @Size(max = 24 * 1024, message = "Icon SVG is too large")
        String svg
) {
    /** A create without an icon (every caller before B541). */
    public CreatePlatformCategoryRequest(String name) {
        this(name, null);
    }
}
