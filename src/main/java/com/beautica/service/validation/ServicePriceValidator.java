package com.beautica.service.validation;

import com.beautica.service.entity.PriceType;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.math.BigDecimal;

/**
 * Validates price-mode invariants for any {@link PricedRequest} DTO.
 *
 * <p>Responsibility: enforces mutual exclusivity between FIXED and RANGE payloads, validates
 * required fields for each mode, and handles the PATCH "price-block absent" shortcut.
 *
 * <p>This class is intentionally stateless — it is safe for the container to share a singleton.
 */
public class ServicePriceValidator implements ConstraintValidator<ServicePriceValid, PricedRequest> {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    @Override
    public boolean isValid(PricedRequest request, ConstraintValidatorContext ctx) {
        if (request == null) {
            return true;
        }

        PriceType priceType = request.priceType();
        BigDecimal price = request.price();
        BigDecimal priceMin = request.priceMin();
        BigDecimal priceMax = request.priceMax();

        boolean allAbsent = priceType == null && price == null && priceMin == null && priceMax == null;
        if (allAbsent) {
            // PATCH semantics: entire price block absent → leave existing data unchanged.
            return true;
        }

        // At least one price field is present — priceType is mandatory from here on.
        if (priceType == null) {
            return fail(ctx, "priceType", "priceType is required when any price field is provided");
        }

        ctx.disableDefaultConstraintViolation();

        return switch (priceType) {
            case FIXED -> validateFixed(price, priceMin, priceMax, ctx);
            case RANGE -> validateRange(priceMin, priceMax, price, ctx);
        };
    }

    private boolean validateFixed(BigDecimal price, BigDecimal priceMin, BigDecimal priceMax,
                                  ConstraintValidatorContext ctx) {
        boolean valid = true;

        if (price == null) {
            addViolation(ctx, "price", "price is required for FIXED pricing");
            valid = false;
        } else if (price.compareTo(ZERO) <= 0) {
            addViolation(ctx, "price", "price must be greater than 0");
            valid = false;
        }

        if (priceMin != null) {
            addViolation(ctx, "priceMin", "priceMin must be null for FIXED pricing");
            valid = false;
        }

        if (priceMax != null) {
            addViolation(ctx, "priceMax", "priceMax must be null for FIXED pricing");
            valid = false;
        }

        return valid;
    }

    private boolean validateRange(BigDecimal priceMin, BigDecimal priceMax, BigDecimal price,
                                  ConstraintValidatorContext ctx) {
        boolean valid = true;

        if (price != null) {
            addViolation(ctx, "price", "price must be null for RANGE pricing");
            valid = false;
        }

        if (priceMin == null) {
            addViolation(ctx, "priceMin", "priceMin is required for RANGE pricing");
            valid = false;
        } else if (priceMin.compareTo(ZERO) <= 0) {
            addViolation(ctx, "priceMin", "priceMin must be greater than 0");
            valid = false;
        }

        if (priceMax == null) {
            addViolation(ctx, "priceMax", "priceMax is required for RANGE pricing");
            valid = false;
        } else if (priceMax.compareTo(ZERO) <= 0) {
            addViolation(ctx, "priceMax", "priceMax must be greater than 0");
            valid = false;
        }

        // Only check priceMax > priceMin when both are valid positive values
        if (valid && priceMin != null && priceMax != null) {
            if (priceMax.compareTo(priceMin) <= 0) {
                addViolation(ctx, "priceMax", "priceMax must be strictly greater than priceMin");
                valid = false;
            }
        }

        return valid;
    }

    private boolean fail(ConstraintValidatorContext ctx, String field, String message) {
        ctx.disableDefaultConstraintViolation();
        addViolation(ctx, field, message);
        return false;
    }

    private void addViolation(ConstraintValidatorContext ctx, String field, String message) {
        ctx.buildConstraintViolationWithTemplate(message)
                .addPropertyNode(field)
                .addConstraintViolation();
    }
}
