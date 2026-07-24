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
 *   <li>{@code initialServiceName} — OPTIONAL free-text name of an initial service-type
 *       the requester wants under the new category. Validated <em>optional</em>:
 *       {@code @Size(max = 255)} matching the {@code service_type_suggestion.suggested_name}
 *       column it eventually populates, plus a control-char ban
 *       ({@code @Pattern("^[^\\p{Cntrl}]*$")}). No {@code @NotBlank} — {@code null} and
 *       blank both pass and are normalized to {@code null} at the service layer. When
 *       present, category approval creates a PENDING {@link com.beautica.service.entity.ServiceTypeSuggestion}
 *       for the now-approved category (no auto-promotion; Phase 16.8 decision).</li>
 * </ul>
 */
public record CreateCategoryRequestRequest(
        @NotBlank(message = "Category name is required")
        @Size(max = 50, message = "Category name must be at most 50 characters")
        @Pattern(
                regexp = "^[A-Z][A-Z0-9_]*$",
                message = "Category name must contain only uppercase letters, digits, or underscores, and start with a letter"
        )
        String name,

        @NotBlank(message = "Display name is required")
        @Size(max = 100, message = "Display name must be at most 100 characters")
        String displayName,

        @Size(max = 255, message = "Initial service name must be at most 255 characters")
        @Pattern(
                regexp = "^[^\\p{Cntrl}]*$",
                message = "Initial service name must not contain control characters"
        )
        String initialServiceName
) {
}
