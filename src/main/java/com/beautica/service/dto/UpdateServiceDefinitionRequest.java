package com.beautica.service.dto;

import com.beautica.service.entity.PriceType;
import com.beautica.service.validation.PricedRequest;
import com.beautica.service.validation.ServicePriceValid;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * PATCH-semantics request DTO for updating a {@link com.beautica.service.entity.ServiceDefinition}.
 *
 * <p>All fields are optional — only non-null fields are applied to the entity.
 * A field explicitly set to {@code null} in JSON is treated as "no change" for that attribute.
 *
 * <h2>Pricing PATCH rules</h2>
 * <ul>
 *   <li>If ALL four price fields ({@code priceType}, {@code price}, {@code priceMin}, {@code priceMax})
 *       are {@code null}, the price block is treated as absent and the existing pricing is preserved.</li>
 *   <li>If ANY price field is non-null, {@code priceType} must be present and the full mode payload must
 *       satisfy the FIXED or RANGE invariants (see {@link com.beautica.service.validation.ServicePriceValid}).</li>
 * </ul>
 *
 * <p>Bean Validation rules:
 * <ul>
 *   <li>{@code name} — {@code @Size(min=1, max=100)}: min=1 rejects empty string (PATCH still
 *       allows null to mean "no change"), max=100 matches {@code @Column(length=100)} on the entity;
 *       {@code @Pattern} blocks control characters.</li>
 *   <li>{@code description} — {@code @Size(max=2000)} matches the entity TEXT column practical limit.</li>
 *   <li>{@code baseDurationMinutes} — {@code @Min(1) @Max(480)} prevents negative/zero durations
 *       and caps the slot calculator overflow risk.</li>
 *   <li>Price fields — {@code @DecimalMin} / {@code @DecimalMax} / {@code @Digits} guard the
 *       DB {@code NUMERIC(10,2)} precision; combined mode-level validation is handled by
 *       {@code @ServicePriceValid} at the class level.</li>
 *   <li>{@code bufferMinutesAfter} — {@code @Min(0) @Max(120)} matches the create-request bounds.</li>
 * </ul>
 */
@ServicePriceValid
public record UpdateServiceDefinitionRequest(
        @Size(min = 1, max = 100, message = "Name must be between 1 and 100 characters")
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "Name must not contain control characters")
        String name,

        @Size(max = 2000, message = "Description must be at most 2000 characters")
        @Pattern(regexp = "^[^\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]*$",
                message = "Description must not contain control characters other than line breaks and tabs")
        String description,

        /**
         * Category name (PATCH — optional). When non-null, the service layer validates
         * that this name exists in {@code platform_categories} with {@code active = true}.
         * Format mirrors the create-request constraint: uppercase letters, digits,
         * underscores, starting with an uppercase letter.
         */
        @Size(max = 100, message = "Category must be at most 100 characters")
        @Pattern(regexp = "^[A-Z][A-Z0-9_]*$",
                 message = "Category must contain only uppercase letters, digits, or underscores, and start with a letter")
        String category,

        @Min(value = 1, message = "Duration must be at least 1 minute")
        @Max(value = 480, message = "Duration must be at most 480 minutes")
        Integer baseDurationMinutes,

        @Min(value = 0, message = "Buffer must be non-negative")
        @Max(value = 120, message = "Buffer must be at most 120 minutes")
        Integer bufferMinutesAfter,

        /** Pricing mode for this PATCH. Must be consistent with the price fields below. */
        PriceType priceType,

        /**
         * FIXED-mode amount (PATCH). Required when {@code priceType = FIXED}; must be null for RANGE.
         * Must be null when the entire price block is absent (all four price fields null).
         */
        @DecimalMin(value = "0.01", inclusive = true, message = "price must be greater than 0.00")
        @DecimalMax(value = "99999999.99", message = "price exceeds maximum allowed value")
        @Digits(integer = 8, fraction = 2, message = "price must have at most 8 integer digits and 2 decimal places")
        BigDecimal price,

        /**
         * RANGE floor (PATCH). Required when {@code priceType = RANGE}; must be null for FIXED.
         */
        @DecimalMin(value = "0.01", inclusive = true, message = "priceMin must be greater than 0.00")
        @DecimalMax(value = "99999999.99", message = "priceMin exceeds maximum allowed value")
        @Digits(integer = 8, fraction = 2, message = "priceMin must have at most 8 integer digits and 2 decimal places")
        BigDecimal priceMin,

        /**
         * RANGE ceiling (PATCH). Required when {@code priceType = RANGE}; must be null for FIXED.
         * Must be strictly greater than {@code priceMin}.
         */
        @DecimalMin(value = "0.01", inclusive = true, message = "priceMax must be greater than 0.00")
        @DecimalMax(value = "99999999.99", message = "priceMax exceeds maximum allowed value")
        @Digits(integer = 8, fraction = 2, message = "priceMax must have at most 8 integer digits and 2 decimal places")
        BigDecimal priceMax,

        /**
         * Optional id of the platform service type to switch this definition to (PATCH).
         *
         * <p>PATCH semantics: {@code null} means "leave the existing service type unchanged" —
         * it never clears an already-set type. When non-null, the service layer resolves the
         * type, asserts it is active, and applies the Phase-16.3 cross-category consistency
         * check: the type must belong to the category this PATCH is moving the definition to
         * (the new {@code category} when the request also changes it, otherwise the existing one).
         *
         * <p>Mirrors {@code CreateServiceDefinitionRequest.serviceTypeId}: optional UUID, no
         * {@code @NotNull} because PATCH is partial.
         */
        @Nullable
        @Schema(
                types = {"string", "null"},
                format = "uuid",
                nullable = true,
                description = "Optional id of the platform service type to switch this service to. "
                        + "Omit or send null to leave the current service type unchanged.")
        UUID serviceTypeId
) implements PricedRequest {
}
