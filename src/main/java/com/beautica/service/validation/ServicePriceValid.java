package com.beautica.service.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Class-level constraint that validates the price-mode invariants of a {@link PricedRequest}.
 *
 * <h2>FIXED mode rules</h2>
 * <ul>
 *   <li>{@code price} is required and must be &gt; 0.</li>
 *   <li>{@code priceMin} and {@code priceMax} must be {@code null}.</li>
 * </ul>
 *
 * <h2>RANGE mode rules</h2>
 * <ul>
 *   <li>{@code priceMin} and {@code priceMax} are required and must be &gt; 0.</li>
 *   <li>{@code priceMax} must be strictly greater than {@code priceMin}.</li>
 *   <li>{@code price} must be {@code null}.</li>
 * </ul>
 *
 * <h2>PATCH (partial update) behaviour</h2>
 * If {@code priceType}, {@code price}, {@code priceMin}, and {@code priceMax} are ALL {@code null}
 * the price block is treated as absent — validation passes and the service layer leaves
 * existing price data unchanged.
 * If ANY of the four fields is non-null, {@code priceType} must be present and the full mode
 * payload must satisfy the rules above.
 */
@Documented
@Constraint(validatedBy = ServicePriceValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ServicePriceValid {
    String message() default "Invalid price configuration";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
