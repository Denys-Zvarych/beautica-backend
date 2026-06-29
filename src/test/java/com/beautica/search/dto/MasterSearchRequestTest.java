package com.beautica.search.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for the {@link MasterSearchRequest} compact constructor.
 *
 * <p>No Spring, no MVC binding — these construct the record directly via its
 * canonical constructor to prove the price/rating scale normalization happens in
 * the compact constructor itself (independent of how the value arrives). The
 * companion {@code SearchControllerTest} proves the same normalization fires
 * <em>before</em> Bean Validation on the real {@code @ModelAttribute} path.</p>
 *
 * <p>Pre-fix (no compact constructor) every "rounds to scale 2" case below would
 * retain its 13-fraction-digit value and trip {@code @Digits(fraction = 2)} on
 * the binding path; here the record simply stored the raw value verbatim.</p>
 */
@DisplayName("MasterSearchRequest — compact-constructor scale normalization")
class MasterSearchRequestTest {

    private static MasterSearchRequest withPrices(BigDecimal minPrice,
                                                  BigDecimal maxPrice,
                                                  BigDecimal minRating) {
        return new MasterSearchRequest(
                null,        // location
                null,        // q
                null,        // category
                null,        // sort
                minPrice,
                maxPrice,
                minRating,
                null,        // page
                null,        // size
                null);       // serviceTypeSlugs
    }

    @Test
    @DisplayName("minPrice float-slider artifact (1700.0000000000002) is normalized to 1700.00 at scale 2")
    void should_normalizeMinPriceToScale2_when_constructedWithFloatArtifact() {
        MasterSearchRequest request = withPrices(new BigDecimal("1700.0000000000002"), null, null);

        assertThat(request.minPrice())
                .as("minPrice normalized to scale 2, value=%s", request.minPrice())
                .isEqualTo(new BigDecimal("1700.00"));
        assertThat(request.minPrice().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("maxPrice float-slider artifact (1700.0000000000002) is normalized to 1700.00 at scale 2")
    void should_normalizeMaxPriceToScale2_when_constructedWithFloatArtifact() {
        MasterSearchRequest request = withPrices(null, new BigDecimal("1700.0000000000002"), null);

        assertThat(request.maxPrice())
                .as("maxPrice normalized to scale 2, value=%s", request.maxPrice())
                .isEqualTo(new BigDecimal("1700.00"));
        assertThat(request.maxPrice().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("minRating float artifact (4.7000000000001) is normalized to 4.70 at scale 2")
    void should_normalizeMinRatingToScale2_when_constructedWithFloatArtifact() {
        MasterSearchRequest request = withPrices(null, null, new BigDecimal("4.7000000000001"));

        assertThat(request.minRating())
                .as("minRating normalized to scale 2, value=%s", request.minRating())
                .isEqualTo(new BigDecimal("4.70"));
        assertThat(request.minRating().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("rounding is HALF_UP: a third fraction digit of 5 rounds the bound up (99.005 → 99.01)")
    void should_roundHalfUp_when_thirdFractionDigitIsFive() {
        MasterSearchRequest request = withPrices(new BigDecimal("99.005"), null, null);

        assertThat(request.minPrice())
                .as("99.005 rounds HALF_UP to 99.01, value=%s", request.minPrice())
                .isEqualTo(new BigDecimal("99.01"));
    }

    @Test
    @DisplayName("null price/rating bounds are preserved as null (no rounding of absent filters)")
    void should_preserveNull_when_priceAndRatingBoundsAbsent() {
        MasterSearchRequest request = withPrices(null, null, null);

        assertThat(request.minPrice()).isNull();
        assertThat(request.maxPrice()).isNull();
        assertThat(request.minRating()).isNull();
    }
}
