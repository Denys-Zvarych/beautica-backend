package com.beautica.service.dto;

import com.beautica.service.entity.PriceType;
import com.beautica.service.validation.PricedRequest;
import com.beautica.service.validation.ServicePriceValid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A single item in a {@link BulkCreateServicesRequest}.
 *
 * <h2>Name (optional, phase 464) — and why no {@code category}</h2>
 * The bulk setup flow is service-type driven: the mobile picker presents platform categories,
 * expands each to its service types, and the master toggles individual types on. The persisted
 * service <em>name</em> defaults to the chosen {@link com.beautica.service.entity.ServiceType}'s
 * Ukrainian display name ({@code nameUk}). Since phase 464 an item may carry an optional custom
 * {@code name}: one owner may hold several copies of a type, and two copies must differ in
 * duration OR name (the V233 index). {@code null}/blank means {@code nameUk}, exactly as before.
 * Its constraints are the single-create {@code name}'s, verbatim. The <em>category</em> is
 * derived from the service type's parent ({@code platform_category_name}); accepting it again
 * per item would only invite an inconsistent pairing, so it is intentionally omitted.
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
        BigDecimal priceMax,

        /**
         * Optional custom name (phase 464). {@code null} or blank → the service type's
         * {@code nameUk}. Same constraints as {@code CreateServiceDefinitionRequest.name}.
         */
        @Size(max = 100, message = "Name must be at most 100 characters")
        @Pattern(regexp = ServiceNameConstraints.NAME_REGEX, message = ServiceNameConstraints.NAME_REGEX_MESSAGE)
        String name
) implements PricedRequest {

    /** Unnamed item — the persisted name is the service type's {@code nameUk} (pre-464 shape). */
    public BulkServiceItemRequest(UUID serviceTypeId, Integer durationMinutes, PriceType priceType,
            BigDecimal price, BigDecimal priceMin, BigDecimal priceMax) {
        this(serviceTypeId, durationMinutes, priceType, price, priceMin, priceMax, null);
    }
}
