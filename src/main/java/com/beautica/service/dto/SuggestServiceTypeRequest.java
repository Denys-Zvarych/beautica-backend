package com.beautica.service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record SuggestServiceTypeRequest(
        @NotBlank
        @Size(max = 255)
        @Pattern(regexp = "^[^\\r\\n]*$", message = "Name must not contain newline characters")
        String name,

        @NotNull UUID categoryId,

        @Size(max = 1000)
        @Pattern(regexp = "^[^\\r\\n]*$", message = "Description must not contain newline characters")
        String description
) {}
