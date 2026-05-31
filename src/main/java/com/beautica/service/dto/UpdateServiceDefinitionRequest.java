package com.beautica.service.dto;

import com.beautica.service.entity.ServiceCategory;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * PATCH-semantics request DTO for updating a {@link com.beautica.service.entity.ServiceDefinition}.
 *
 * <p>All fields are optional — only non-null fields are applied to the entity.
 * A field explicitly set to {@code null} in JSON is treated as "no change" for that attribute.
 *
 * <p>Bean Validation rules follow the DTO validation matrix (anti-bug §A):
 * <ul>
 *   <li>{@code name} — {@code @Size(min=1, max=100)}: min=1 rejects empty string (PATCH still
 *       allows null to mean "no change"), max=100 matches {@code @Column(length=100)} on the entity;
 *       {@code @Pattern} blocks control characters.</li>
 *   <li>{@code description} — {@code @Size(max=2000)} matches the entity TEXT column practical limit.</li>
 *   <li>{@code baseDurationMinutes} — {@code @Min(1) @Max(480)} prevents negative/zero durations
 *       and caps the slot calculator overflow risk.</li>
 *   <li>{@code basePrice} — {@code @DecimalMin} / {@code @DecimalMax} / {@code @Digits} guard the
 *       DB {@code NUMERIC(10,2)} precision; all three are present because Bean Validation skips
 *       null targets silently ({@code @DecimalMin} alone is insufficient).</li>
 *   <li>{@code bufferMinutesAfter} — {@code @Min(0) @Max(120)} matches the create-request bounds.</li>
 * </ul>
 */
public record UpdateServiceDefinitionRequest(
        @Size(min = 1, max = 100, message = "Name must be between 1 and 100 characters")
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "Name must not contain control characters")
        String name,

        @Size(max = 2000, message = "Description must be at most 2000 characters")
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "Description must not contain control characters")
        String description,

        // ServiceCategory is an enum; Jackson deserialises it; GlobalExceptionHandler
        // translates HttpMessageNotReadableException to a generic 400 (anti-bug §A).
        ServiceCategory category,

        @Min(value = 1, message = "Duration must be at least 1 minute")
        @Max(value = 480, message = "Duration must be at most 480 minutes")
        Integer baseDurationMinutes,

        @DecimalMin(value = "0.00", message = "Price must be non-negative")
        @DecimalMax(value = "99999999.99", message = "Price exceeds maximum allowed value")
        @Digits(integer = 8, fraction = 2, message = "Price must have at most 8 integer digits and 2 decimal places")
        BigDecimal basePrice,

        @Min(value = 0, message = "Buffer must be non-negative")
        @Max(value = 120, message = "Buffer must be at most 120 minutes")
        Integer bufferMinutesAfter
) {
}
