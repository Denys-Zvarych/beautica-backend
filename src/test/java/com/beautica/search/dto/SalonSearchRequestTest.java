package com.beautica.search.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for the {@link SalonSearchRequest} compact constructor.
 *
 * <p>Salon search has no rating filter, so only the two price bounds are
 * normalized to scale 2 ({@code HALF_UP}). Mirrors {@link MasterSearchRequestTest}
 * to keep the two request DTOs' normalization contract provably identical.</p>
 */
@DisplayName("SalonSearchRequest — compact-constructor scale normalization")
class SalonSearchRequestTest {

    private static SalonSearchRequest withPrices(BigDecimal minPrice, BigDecimal maxPrice) {
        return new SalonSearchRequest(
                null,        // location
                null,        // q
                null,        // category
                null,        // sort
                minPrice,
                maxPrice,
                null,        // page
                null,        // size
                null);       // serviceTypeSlugs
    }

    @Test
    @DisplayName("minPrice float-slider artifact (1700.0000000000002) is normalized to 1700.00 at scale 2")
    void should_normalizeMinPriceToScale2_when_constructedWithFloatArtifact() {
        SalonSearchRequest request = withPrices(new BigDecimal("1700.0000000000002"), null);

        assertThat(request.minPrice())
                .as("minPrice normalized to scale 2, value=%s", request.minPrice())
                .isEqualTo(new BigDecimal("1700.00"));
        assertThat(request.minPrice().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("maxPrice float-slider artifact (1700.0000000000002) is normalized to 1700.00 at scale 2")
    void should_normalizeMaxPriceToScale2_when_constructedWithFloatArtifact() {
        SalonSearchRequest request = withPrices(null, new BigDecimal("1700.0000000000002"));

        assertThat(request.maxPrice())
                .as("maxPrice normalized to scale 2, value=%s", request.maxPrice())
                .isEqualTo(new BigDecimal("1700.00"));
        assertThat(request.maxPrice().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("rounding is HALF_UP: a third fraction digit of 5 rounds the bound up (99.005 → 99.01)")
    void should_roundHalfUp_when_thirdFractionDigitIsFive() {
        SalonSearchRequest request = withPrices(new BigDecimal("99.005"), null);

        assertThat(request.minPrice())
                .as("99.005 rounds HALF_UP to 99.01, value=%s", request.minPrice())
                .isEqualTo(new BigDecimal("99.01"));
    }

    @Test
    @DisplayName("null price bounds are preserved as null (no rounding of absent filters)")
    void should_preserveNull_when_priceBoundsAbsent() {
        SalonSearchRequest request = withPrices(null, null);

        assertThat(request.minPrice()).isNull();
        assertThat(request.maxPrice()).isNull();
    }
}
