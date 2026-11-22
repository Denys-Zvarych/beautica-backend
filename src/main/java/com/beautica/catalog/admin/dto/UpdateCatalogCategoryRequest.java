package com.beautica.catalog.admin.dto;

import com.beautica.service.dto.ServiceNameConstraints;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * B504 — body of {@code PATCH /api/v1/internal/catalog/categories/{name}}. Every field is optional ({@code null} =
 * leave unchanged); an empty body is a 400. {@code displayName} follows SVC-07/SVC-08 on the raw input (allow-list,
 * 100 chars = {@code platform_categories.display_name}); the service re-validates the canonical (NFKC) form and a
 * blank value is a 400. {@code active}: {@code false} hides, {@code true} unhides.
 */
public record UpdateCatalogCategoryRequest(
        @Size(max = 100, message = "Name must be at most 100 characters")
        @Pattern(regexp = ServiceNameConstraints.NAME_REGEX, message = ServiceNameConstraints.NAME_REGEX_MESSAGE)
        String displayName,

        Boolean active
) {}
