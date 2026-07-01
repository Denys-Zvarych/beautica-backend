package com.beautica.service.util;

import com.beautica.service.entity.PriceType;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pure utility for formatting a service price into a human-readable Ukrainian string.
 *
 * <ul>
 *   <li>FIXED: {@code "500 грн"}, {@code "500.50 грн"}</li>
 *   <li>RANGE: {@code "від 500 до 800 грн"}, {@code "від 500.50 до 800.99 грн"}</li>
 * </ul>
 *
 * <p>Trailing {@code .00} is stripped — whole hryvnia amounts are displayed without decimals.
 * Non-zero fractional parts are kept as two decimal places.
 *
 * <p>This class has no mutable state; all methods are static and safe for concurrent use.
 */
public final class PriceDisplayFormatter {

    private static final String CURRENCY_SUFFIX = " грн";

    private PriceDisplayFormatter() {
        // utility class — no instantiation
    }

    /**
     * Formats the price display string for the given pricing mode.
     *
     * @param priceType the pricing mode; must not be {@code null}
     * @param priceMin  the minimum (or only) price; must not be {@code null}
     * @param priceMax  the maximum price; required when {@code priceType == RANGE}, ignored for FIXED
     * @return a formatted display string, e.g. {@code "500 грн"} or {@code "від 500 до 800 грн"}
     * @throws IllegalArgumentException if required arguments are null or inconsistent
     */
    public static String format(PriceType priceType, BigDecimal priceMin, BigDecimal priceMax) {
        if (priceType == null) throw new IllegalArgumentException("priceType must not be null");
        if (priceMin == null) throw new IllegalArgumentException("priceMin must not be null");

        return switch (priceType) {
            case FIXED -> formatAmount(priceMin) + CURRENCY_SUFFIX;
            case RANGE -> {
                if (priceMax == null) throw new IllegalArgumentException("priceMax must not be null for RANGE");
                yield "від " + formatAmount(priceMin) + " до " + formatAmount(priceMax) + CURRENCY_SUFFIX;
            }
        };
    }

    /**
     * Formats a single monetary amount: strips {@code .00} for whole amounts,
     * keeps two decimal places otherwise.
     */
    static String formatAmount(BigDecimal amount) {
        BigDecimal scaled = amount.setScale(2, RoundingMode.HALF_UP);
        if (scaled.scale() == 2 && scaled.stripTrailingZeros().scale() <= 0) {
            // Whole number — display without decimal part
            return scaled.toBigInteger().toString();
        }
        return scaled.toPlainString();
    }
}
