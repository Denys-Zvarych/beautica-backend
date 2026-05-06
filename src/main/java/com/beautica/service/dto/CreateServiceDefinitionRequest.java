package com.beautica.service.dto;

import com.beautica.service.entity.ServiceCategory;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record CreateServiceDefinitionRequest(
        @NotBlank String name,
        String description,
        ServiceCategory category,
        @NotNull @Positive int baseDurationMinutes,
        BigDecimal basePrice,
        @Min(0) int bufferMinutesAfter
) {
}
