package com.beautica.service.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record AssignServiceToMasterRequest(
        @NotNull UUID serviceDefId,

        @DecimalMin(value = "0.01", message = "Price override must be positive")
        @DecimalMax(value = "99999.99", message = "Price override exceeds maximum")
        @Digits(integer = 7, fraction = 2)
        BigDecimal priceOverride,

        @Min(value = 1, message = "Duration override must be at least 1 minute")
        @Max(value = 480, message = "Duration override cannot exceed 480 minutes")
        Integer durationOverrideMinutes
) {
}
