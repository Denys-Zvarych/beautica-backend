package com.beautica.service.util;

import com.beautica.service.entity.PriceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link PriceDisplayFormatter}.
 *
 * <p>Covers: FIXED whole amounts, FIXED decimal amounts, RANGE whole amounts,
 * RANGE decimal amounts, trailing-zero stripping, null-guard contract.
 */
@DisplayName("PriceDisplayFormatter — unit")
class PriceDisplayFormatterTest {

    // ── FIXED mode ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("FIXED whole hryvnia — no decimal suffix")
    void should_formatFixed_when_wholeHryvnia() {
        String result = PriceDisplayFormatter.format(PriceType.FIXED, new BigDecimal("500.00"), null);

        assertThat(result).isEqualTo("500 ₴");
    }

    @Test
    @DisplayName("FIXED whole hryvnia without trailing zeros in input")
    void should_formatFixed_when_wholeHryvniaNoScale() {
        String result = PriceDisplayFormatter.format(PriceType.FIXED, new BigDecimal("300"), null);

        assertThat(result).isEqualTo("300 ₴");
    }

    @Test
    @DisplayName("FIXED with non-zero decimal — keeps two decimal places")
    void should_formatFixed_when_decimalAmount() {
        String result = PriceDisplayFormatter.format(PriceType.FIXED, new BigDecimal("500.50"), null);

        assertThat(result).isEqualTo("500.50 ₴");
    }

    @Test
    @DisplayName("FIXED small amount — 0.01 ₴")
    void should_formatFixed_when_smallDecimalAmount() {
        String result = PriceDisplayFormatter.format(PriceType.FIXED, new BigDecimal("0.01"), null);

        assertThat(result).isEqualTo("0.01 ₴");
    }

    @Test
    @DisplayName("FIXED large whole amount")
    void should_formatFixed_when_largeWholeAmount() {
        String result = PriceDisplayFormatter.format(PriceType.FIXED, new BigDecimal("99999999.00"), null);

        assertThat(result).isEqualTo("99999999 ₴");
    }

    // ── RANGE mode ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("RANGE whole hryvnia — від N до M ₴")
    void should_formatRange_when_wholeHryvnia() {
        String result = PriceDisplayFormatter.format(PriceType.RANGE, new BigDecimal("500.00"), new BigDecimal("800.00"));

        assertThat(result).isEqualTo("від 500 до 800 ₴");
    }

    @Test
    @DisplayName("RANGE with decimal values — від 500.50 до 800.99 ₴")
    void should_formatRange_when_decimalAmounts() {
        String result = PriceDisplayFormatter.format(PriceType.RANGE, new BigDecimal("500.50"), new BigDecimal("800.99"));

        assertThat(result).isEqualTo("від 500.50 до 800.99 ₴");
    }

    @Test
    @DisplayName("RANGE with mixed whole/decimal — від 500 до 800.50 ₴")
    void should_formatRange_when_mixedAmounts() {
        String result = PriceDisplayFormatter.format(PriceType.RANGE, new BigDecimal("500.00"), new BigDecimal("800.50"));

        assertThat(result).isEqualTo("від 500 до 800.50 ₴");
    }

    // ── formatAmount helper (package-private) ─────────────────────────────────

    @Test
    @DisplayName("formatAmount strips .00 from whole amounts")
    void should_stripTrailingZeros_when_wholeAmount() {
        assertThat(PriceDisplayFormatter.formatAmount(new BigDecimal("350.00"))).isEqualTo("350");
    }

    @Test
    @DisplayName("formatAmount preserves .50 non-zero decimal")
    void should_preserveDecimals_when_nonZeroFraction() {
        assertThat(PriceDisplayFormatter.formatAmount(new BigDecimal("350.50"))).isEqualTo("350.50");
    }

    @Test
    @DisplayName("formatAmount rounds to two decimals HALF_UP — 350.555 → 350.56, 350.554 → 350.55")
    void should_roundHalfUp_when_inputHasMoreThanTwoDecimals() {
        // Pins setScale(2, HALF_UP). Regressing to RoundingMode.UNNECESSARY would
        // throw ArithmeticException on these >2-decimal inputs instead of rounding.
        assertThat(PriceDisplayFormatter.formatAmount(new BigDecimal("350.555"))).isEqualTo("350.56");
        assertThat(PriceDisplayFormatter.formatAmount(new BigDecimal("350.554"))).isEqualTo("350.55");
    }

    // ── null guards ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("throws IllegalArgumentException when priceType is null")
    void should_throwIllegalArgument_when_priceTypeNull() {
        assertThatThrownBy(() -> PriceDisplayFormatter.format(null, new BigDecimal("500.00"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("priceType");
    }

    @Test
    @DisplayName("throws IllegalArgumentException when priceMin is null")
    void should_throwIllegalArgument_when_priceMinNull() {
        assertThatThrownBy(() -> PriceDisplayFormatter.format(PriceType.FIXED, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("priceMin");
    }

    @Test
    @DisplayName("throws IllegalArgumentException for RANGE when priceMax is null")
    void should_throwIllegalArgument_when_rangeAndPriceMaxNull() {
        assertThatThrownBy(() -> PriceDisplayFormatter.format(PriceType.RANGE, new BigDecimal("500.00"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("priceMax");
    }
}
