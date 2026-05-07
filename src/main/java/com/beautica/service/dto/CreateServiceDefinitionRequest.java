package com.beautica.service.dto;

import com.beautica.service.entity.ServiceCategory;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateServiceDefinitionRequest(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 2000) String description,
        ServiceCategory category,
        @NotNull @Positive @Max(480) int baseDurationMinutes,
        @DecimalMin("0.00") @DecimalMax("99999999.99") @Digits(integer = 8, fraction = 2) BigDecimal basePrice,
        @Min(0) @Max(120) int bufferMinutesAfter,
        @Nullable UUID serviceTypeId
) {
}
