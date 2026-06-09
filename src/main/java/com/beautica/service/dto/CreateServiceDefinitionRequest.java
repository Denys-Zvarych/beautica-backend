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
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Request DTO for creating a new {@link com.beautica.service.entity.ServiceDefinition}.
 *
 * <h2>Pricing fields</h2>
 * Exactly one pricing mode must be supplied:
 * <ul>
 *   <li><b>FIXED</b>: set {@code priceType = FIXED} and {@code price}. Leave {@code priceMin}
 *       and {@code priceMax} null.</li>
 *   <li><b>RANGE</b>: set {@code priceType = RANGE}, {@code priceMin}, and {@code priceMax}.
 *       Leave {@code price} null. {@code priceMax} must be strictly greater than
 *       {@code priceMin}.</li>
 * </ul>
 *
 * <h2>Category validation</h2>
 * The {@code category} field is a {@code String} (not the {@link com.beautica.service.entity.ServiceCategory}
 * enum) so that dynamically-added platform categories created via
 * {@code POST /api/v1/internal/service-categories} are accepted without a code redeploy.
 * Bean Validation ensures the string format matches the DB CHECK constraint
 * ({@code ^[A-Z][A-Z0-9_]*$}). A second validation pass at the service layer
 * checks that the name exists in {@code platform_categories} with {@code active = true}.
 */
@ServicePriceValid
public record CreateServiceDefinitionRequest(
        /**
         * Custom service name (optional). When null or blank, the service layer defaults
         * the persisted name to the selected service type's Ukrainian display name
         * ({@code ServiceType.nameUk}). If no name is supplied and no service type is
         * available to derive a default, the service layer rejects the request with a
         * "Name or service type is required" validation error.
         */
        @Size(max = 100, message = "Name must be at most 100 characters")
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "Name must not contain control characters")
        String name,

        @Size(max = 2000, message = "Description must be at most 2000 characters")
        @Pattern(regexp = "^[^\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]*$",
                message = "Description must not contain control characters other than line breaks and tabs")
        String description,

        /**
         * Category name — must be an active entry in {@code platform_categories}.
         * Format: uppercase letters, digits, underscores; starts with an uppercase letter.
         * The service layer rejects unknown or inactive values with a 400.
         */
        @NotBlank(message = "Category is required")
        @Size(max = 100, message = "Category must be at most 100 characters")
        @Pattern(regexp = "^[A-Z][A-Z0-9_]*$",
                 message = "Category must contain only uppercase letters, digits, or underscores, and start with a letter")
        String category,

        @NotNull(message = "Duration is required")
        @Positive(message = "Duration must be a positive number of minutes")
        @Max(value = 480, message = "Duration must be at most 480 minutes (8 hours)")
        int baseDurationMinutes,

        @Min(value = 0, message = "Buffer must be non-negative")
        @Max(value = 120, message = "Buffer must be at most 120 minutes")
        int bufferMinutesAfter,

        /** Pricing mode — required. Must be consistent with the price fields below. */
        @NotNull(message = "Price type is required (FIXED or RANGE)")
        PriceType priceType,

        /**
         * FIXED-mode amount. Required when {@code priceType = FIXED}; must be null for RANGE.
         * Validated by {@link com.beautica.service.validation.ServicePriceValidator}.
         */
        @DecimalMin(value = "0.01", inclusive = true, message = "Price must be at least 0.01")
        @DecimalMax(value = "99999999.99", message = "Price exceeds the maximum allowed value")
        @Digits(integer = 8, fraction = 2, message = "Price must have at most 8 integer digits and 2 decimal places")
        BigDecimal price,

        /**
         * RANGE floor. Required when {@code priceType = RANGE}; must be null for FIXED.
         * Validated by {@link com.beautica.service.validation.ServicePriceValidator}.
         */
        @DecimalMin(value = "0.01", inclusive = true, message = "Minimum price must be at least 0.01")
        @DecimalMax(value = "99999999.99", message = "Minimum price exceeds the maximum allowed value")
        @Digits(integer = 8, fraction = 2, message = "Minimum price must have at most 8 integer digits and 2 decimal places")
        BigDecimal priceMin,

        /**
         * RANGE ceiling. Required when {@code priceType = RANGE}; must be null for FIXED.
         * Must be strictly greater than {@code priceMin}.
         * Validated by {@link com.beautica.service.validation.ServicePriceValidator}.
         */
        @DecimalMin(value = "0.01", inclusive = true, message = "Maximum price must be at least 0.01")
        @DecimalMax(value = "99999999.99", message = "Maximum price exceeds the maximum allowed value")
        @Digits(integer = 8, fraction = 2, message = "Maximum price must have at most 8 integer digits and 2 decimal places")
        BigDecimal priceMax,

        @Nullable
        @Schema(
                types = {"string", "null"},
                format = "uuid",
                nullable = true,
                description = "Optional id of the chosen platform service type. "
                        + "Omit or send null when the master skips the (optional) service-type picker.")
        UUID serviceTypeId
) implements PricedRequest {
}
