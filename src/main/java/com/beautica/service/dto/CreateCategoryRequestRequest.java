package com.beautica.service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/service-categories/requests} — a master or
 * salon owner suggesting a new platform category.
 *
 * <p>Validation (anti-bug §A):
 * <ul>
 *   <li>{@code name} — {@code @NotBlank} + {@code @Size(max = 50)} (well within the
 *       DB {@code VARCHAR(100)}) + {@code @Pattern("^[A-Z][A-Z0-9_]*$")} mirroring the
 *       V64 CHECK constraint. {@code @Pattern} skips null, so {@code @NotBlank} covers
 *       that path. The service uppercase-normalizes before persisting.</li>
 *   <li>{@code displayName} — {@code @NotBlank} + {@code @Size(max = 100)} matching the
 *       {@code @Column(length = 100)} on the entity, preventing a
 *       {@code DataIntegrityViolationException} (500) from an oversized value.</li>
 * </ul>
 */
public record CreateCategoryRequestRequest(
        @NotBlank
        @Size(max = 50, message = "Category name must be at most 50 characters")
        @Pattern(
                regexp = "^[A-Z][A-Z0-9_]*$",
                message = "Category name must contain only uppercase letters, digits, or underscores, and start with a letter"
        )
        String name,

        @NotBlank
        @Size(max = 100, message = "Display name must be at most 100 characters")
        String displayName
) {
}
