package com.beautica.service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record SuggestServiceTypeRequest(
        @NotBlank
        @Size(max = 255)
        // Full control-char ban (§D) — the prior narrow pattern only rejected \r\n\t
        // but allowed NUL (0x00) and other control bytes that produce a DB 500.
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "Name must not contain control characters")
        String name,

        @NotNull UUID categoryId,

        @Size(max = 1000)
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "Description must not contain control characters")
        String description
) {}
