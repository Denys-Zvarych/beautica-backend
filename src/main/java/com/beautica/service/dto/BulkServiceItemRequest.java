package com.beautica.service.dto;

import com.beautica.service.entity.PriceType;
import com.beautica.service.validation.PricedRequest;
import com.beautica.service.validation.ServicePriceValid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A single item in a {@link BulkCreateServicesRequest}.
 *
 * <h2>Why no {@code name} or {@code category} here</h2>
 * Unlike {@link CreateServiceDefinitionRequest}, the bulk setup flow is
 * service-type driven: the mobile picker presents platform categories, expands each to
 * its service types, and the master toggles individual types on. The persisted service
 * <em>name</em> is therefore always derived from the chosen {@link com.beautica.service.entity.ServiceType}'s
 * Ukrainian display name ({@code nameUk}) — no free-text name is accepted. The
 * <em>category</em> is likewise derived from the service type's parent
 * ({@code platform_category_name}); accepting it again per item would only invite an
 * inconsistent pairing, so it is intentionally omitted.
 *
 * <h2>Pricing</h2>
 * Reuses the existing flexible-pricing contract via {@link ServicePriceValid}:
 * FIXED → {@code price}; RANGE → {@code priceMin}/{@code priceMax} (with {@code base_price}
 * = {@code priceMin} as the canonical floor). The price-mode invariants are validated per
 * item exactly as they are for single create.
 */
@ServicePriceValid
public record BulkServiceItemRequest(

        /** Active platform service type to create this service from. Required. */
        @NotNull(message = "Service type id is required")
        UUID serviceTypeId,

        @NotNull(message = "Duration is required")
        @Positive(message = "Duration must be a positive number of minutes")
        @Max(value = 480, message = "Duration must be at most 480 minutes (8 hours)")
        Integer durationMinutes,

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
         * Stored as {@code base_price} (canonical floor). Validated by
         * {@link com.beautica.service.validation.ServicePriceValidator}.
         */
        @DecimalMin(value = "0.01", inclusive = true, message = "Minimum price must be at least 0.01")
        @DecimalMax(value = "99999999.99", message = "Minimum price exceeds the maximum allowed value")
        @Digits(integer = 8, fraction = 2, message = "Minimum price must have at most 8 integer digits and 2 decimal places")
        BigDecimal priceMin,

        /**
         * RANGE ceiling. Required when {@code priceType = RANGE}; must be null for FIXED.
         * Must be strictly greater than {@code priceMin}. Validated by
         * {@link com.beautica.service.validation.ServicePriceValidator}.
         */
        @DecimalMin(value = "0.01", inclusive = true, message = "Maximum price must be at least 0.01")
        @DecimalMax(value = "99999999.99", message = "Maximum price exceeds the maximum allowed value")
        @Digits(integer = 8, fraction = 2, message = "Maximum price must have at most 8 integer digits and 2 decimal places")
        BigDecimal priceMax
) implements PricedRequest {
}
