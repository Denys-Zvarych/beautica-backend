package com.beautica.catalog.admin.dto;

import com.beautica.service.dto.ServiceNameConstraints;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * B502 — body of {@code PATCH /api/v1/internal/catalog/requests/{kind}/{id}}.
 *
 * <p>{@code name} follows SVC-07/SVC-08 (allow-list on the raw input, 100 chars); the service re-validates the
 * canonical form. {@code initialServiceName} applies to CATEGORY only (400 on SERVICE_TYPE): absent/{@code null}
 * keeps the current value, a blank string clears it; 255 = {@code service_type_suggestion.suggested_name}, the column
 * it eventually feeds.
 */
public record EditRequestNameRequest(
        @NotBlank(message = "Name is required")
        @Size(max = 100, message = "Name must be at most 100 characters")
        @Pattern(regexp = ServiceNameConstraints.NAME_REGEX, message = ServiceNameConstraints.NAME_REGEX_MESSAGE)
        String name,

        @Size(max = 255, message = "Initial service name must be at most 255 characters")
        @Pattern(regexp = ServiceNameConstraints.NAME_REGEX, message = ServiceNameConstraints.NAME_REGEX_MESSAGE)
        String initialServiceName
) {}
