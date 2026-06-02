package com.beautica.service.validation;

import com.beautica.service.entity.PriceType;

import java.math.BigDecimal;

/**
 * Marker interface implemented by request records that carry price-mode fields.
 *
 * <p>{@link ServicePriceValidator} uses this interface so the validation logic is
 * shared between {@code CreateServiceDefinitionRequest} and {@code UpdateServiceDefinitionRequest}
 * without duplication.
 *
 * <p>Field semantics:
 * <ul>
 *   <li>{@link #priceType()} — the pricing mode ({@code FIXED} or {@code RANGE}); required on CREATE,
 *       conditionally required on PATCH (must be present if any other price field is present).</li>
 *   <li>{@link #price()} — FIXED-mode amount; must be {@code null} for RANGE.</li>
 *   <li>{@link #priceMin()} — RANGE floor; must be {@code null} for FIXED.</li>
 *   <li>{@link #priceMax()} — RANGE ceiling; must be {@code null} for FIXED.</li>
 * </ul>
 */
public interface PricedRequest {
    PriceType priceType();

    BigDecimal price();

    BigDecimal priceMin();

    BigDecimal priceMax();
}
