package com.beautica.service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SuggestServiceTypeRequest(
        @NotBlank(message = "Name is required")
        @Size(max = 255, message = "Name must be at most 255 characters")
        // Full control-char ban (§D) — the prior narrow pattern only rejected \r\n\t
        // but allowed NUL (0x00) and other control bytes that produce a DB 500.
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "Name must not contain control characters")
        String name,

        // System-B category-name slug, validated against platform_categories
        // (active + APPROVED) in the service layer. Same control-char ban as the
        // catalog name/slug fields; @Size matches the platform_categories.name cap.
        @NotBlank(message = "Category name is required")
        @Size(max = 64, message = "Category name must be at most 64 characters")
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "Category name must not contain control characters")
        String categoryName,

        @Size(max = 1000, message = "Description must be at most 1000 characters")
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "Description must not contain control characters")
        String description
) {}
