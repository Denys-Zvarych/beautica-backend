package com.beautica.catalog.admin.dto;

import com.beautica.service.dto.ServiceNameConstraints;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * B504 — body of {@code PATCH /api/v1/internal/catalog/service-types/{id}}. Every field is optional ({@code null} =
 * leave unchanged); an empty body is a 400. Names follow SVC-07/SVC-08 on the raw input (allow-list, 100 chars, the
 * cap a suggested type name has); the service re-validates the canonical (NFKC) form. A blank {@code nameUk} is a 400,
 * a blank {@code nameEn} clears it (stored as {@code ""}: the column is NOT NULL DEFAULT ''). The {@code slug} is not editable. {@code active}: {@code false} hides,
 * {@code true} unhides.
 */
public record UpdateCatalogServiceTypeRequest(
        @Size(max = 100, message = "Name must be at most 100 characters")
        @Pattern(regexp = ServiceNameConstraints.NAME_REGEX, message = ServiceNameConstraints.NAME_REGEX_MESSAGE)
        String nameUk,

        @Size(max = 100, message = "English name must be at most 100 characters")
        @Pattern(regexp = ServiceNameConstraints.NAME_REGEX, message = ServiceNameConstraints.NAME_REGEX_MESSAGE)
        String nameEn,

        Boolean active
) {}
