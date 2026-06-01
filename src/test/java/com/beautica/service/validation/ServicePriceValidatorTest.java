package com.beautica.service.validation;

import com.beautica.service.entity.PriceType;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ServicePriceValidator}.
 *
 * <p>Tests exercise the validator directly through Jakarta Validation by supplying
 * ad-hoc {@link PricedRequest} implementations — no Spring context required.
 *
 * <p>Coverage matrix:
 * <ul>
 *   <li>FIXED: valid, missing price, price is zero, spurious priceMin, spurious priceMax</li>
 *   <li>RANGE: valid, missing priceMin, missing priceMax, max &lt;= min, price set (spurious)</li>
 *   <li>PATCH: entire block absent (valid), partial block without priceType (invalid)</li>
 * </ul>
 */
@DisplayName("ServicePriceValidator — unit")
class ServicePriceValidatorTest {

    private static Validator validator;

    @BeforeAll
    static void buildValidator() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    // ── FIXED — valid ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("FIXED with price and null range fields — valid")
    void should_passValidation_when_fixedWithPriceOnly() {
        var req = request(PriceType.FIXED, new BigDecimal("350.00"), null, null);

        Set<ConstraintViolation<TestPricedRequest>> violations = validator.validate(req);

        assertThat(violations).isEmpty();
    }

    // ── FIXED — invalid ───────────────────────────────────────────────────────

    @Test
    @DisplayName("FIXED without price — validation fails on price field")
    void should_failValidation_when_fixedMissingPrice() {
        var req = request(PriceType.FIXED, null, null, null);

        Set<ConstraintViolation<TestPricedRequest>> violations = validator.validate(req);

        assertThat(violations).isNotEmpty();
        assertThat(violations).extracting(v -> v.getPropertyPath().toString()).contains("price");
    }

    @Test
    @DisplayName("FIXED with price=0 — validation fails")
    void should_failValidation_when_fixedPriceIsZero() {
        var req = request(PriceType.FIXED, BigDecimal.ZERO, null, null);

        Set<ConstraintViolation<TestPricedRequest>> violations = validator.validate(req);

        assertThat(violations).isNotEmpty();
        assertThat(violations).extracting(v -> v.getPropertyPath().toString()).contains("price");
    }

    @Test
    @DisplayName("FIXED with non-null priceMax — validation fails on priceMax")
    void should_failValidation_when_fixedWithPriceMax() {
        var req = request(PriceType.FIXED, new BigDecimal("350.00"), null, new BigDecimal("600.00"));

        Set<ConstraintViolation<TestPricedRequest>> violations = validator.validate(req);

        assertThat(violations).isNotEmpty();
        assertThat(violations).extracting(v -> v.getPropertyPath().toString()).contains("priceMax");
    }

    @Test
    @DisplayName("FIXED with non-null priceMin — validation fails on priceMin")
    void should_failValidation_when_fixedWithPriceMin() {
        var req = request(PriceType.FIXED, new BigDecimal("350.00"), new BigDecimal("200.00"), null);

        Set<ConstraintViolation<TestPricedRequest>> violations = validator.validate(req);

        assertThat(violations).isNotEmpty();
        assertThat(violations).extracting(v -> v.getPropertyPath().toString()).contains("priceMin");
    }

    // ── RANGE — valid ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("RANGE with priceMin < priceMax and null price — valid")
    void should_passValidation_when_rangeWithMinLessThanMax() {
        var req = request(PriceType.RANGE, null, new BigDecimal("500.00"), new BigDecimal("800.00"));

        Set<ConstraintViolation<TestPricedRequest>> violations = validator.validate(req);

        assertThat(violations).isEmpty();
    }

    // ── RANGE — invalid ───────────────────────────────────────────────────────

    @Test
    @DisplayName("RANGE with priceMax == priceMin — strict inequality required")
    void should_failValidation_when_rangeMaxEqualsMin() {
        var req = request(PriceType.RANGE, null, new BigDecimal("500.00"), new BigDecimal("500.00"));

        Set<ConstraintViolation<TestPricedRequest>> violations = validator.validate(req);

        assertThat(violations).isNotEmpty();
        assertThat(violations).extracting(v -> v.getPropertyPath().toString()).contains("priceMax");
    }

    @Test
    @DisplayName("RANGE with priceMax < priceMin — fails")
    void should_failValidation_when_rangeMaxLessThanMin() {
        var req = request(PriceType.RANGE, null, new BigDecimal("800.00"), new BigDecimal("500.00"));

        Set<ConstraintViolation<TestPricedRequest>> violations = validator.validate(req);

        assertThat(violations).isNotEmpty();
        assertThat(violations).extracting(v -> v.getPropertyPath().toString()).contains("priceMax");
    }

    @Test
    @DisplayName("RANGE with missing priceMin — fails")
    void should_failValidation_when_rangeMissingMin() {
        var req = request(PriceType.RANGE, null, null, new BigDecimal("800.00"));

        Set<ConstraintViolation<TestPricedRequest>> violations = validator.validate(req);

        assertThat(violations).isNotEmpty();
        assertThat(violations).extracting(v -> v.getPropertyPath().toString()).contains("priceMin");
    }

    @Test
    @DisplayName("RANGE with missing priceMax — fails")
    void should_failValidation_when_rangeMissingMax() {
        var req = request(PriceType.RANGE, null, new BigDecimal("500.00"), null);

        Set<ConstraintViolation<TestPricedRequest>> violations = validator.validate(req);

        assertThat(violations).isNotEmpty();
        assertThat(violations).extracting(v -> v.getPropertyPath().toString()).contains("priceMax");
    }

    @Test
    @DisplayName("RANGE with spurious price field — fails on price")
    void should_failValidation_when_rangeWithPriceSet() {
        var req = request(PriceType.RANGE, new BigDecimal("350.00"), new BigDecimal("500.00"), new BigDecimal("800.00"));

        Set<ConstraintViolation<TestPricedRequest>> violations = validator.validate(req);

        assertThat(violations).isNotEmpty();
        assertThat(violations).extracting(v -> v.getPropertyPath().toString()).contains("price");
    }

    // ── PATCH semantics ────────────────────────────────────────────────────────

    @Test
    @DisplayName("All four price fields null — PATCH price-block absent — valid")
    void should_passValidation_when_entirePriceBlockAbsent() {
        var req = request(null, null, null, null);

        Set<ConstraintViolation<TestPricedRequest>> violations = validator.validate(req);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("price present without priceType — fails on priceType")
    void should_failValidation_when_priceSetWithoutPriceType() {
        var req = request(null, new BigDecimal("350.00"), null, null);

        Set<ConstraintViolation<TestPricedRequest>> violations = validator.validate(req);

        assertThat(violations).isNotEmpty();
        assertThat(violations).extracting(v -> v.getPropertyPath().toString()).contains("priceType");
    }

    @Test
    @DisplayName("priceMin present without priceType — fails on priceType")
    void should_failValidation_when_priceMinSetWithoutPriceType() {
        var req = request(null, null, new BigDecimal("500.00"), null);

        Set<ConstraintViolation<TestPricedRequest>> violations = validator.validate(req);

        assertThat(violations).isNotEmpty();
        assertThat(violations).extracting(v -> v.getPropertyPath().toString()).contains("priceType");
    }

    // ── MEDIUM-3: priceMax alone (no priceType) ────────────────────────────────

    @Test
    @DisplayName("priceMax present without priceType — fails on priceType")
    void should_failValidation_when_priceMaxSetWithoutPriceType() {
        // Arrange: only priceMax is set — priceType, price, priceMin are all null.
        // ServicePriceValidator must detect that a price field is present without
        // a priceType and produce a violation on the priceType path.
        var req = request(null, null, null, new BigDecimal("800.00"));

        Set<ConstraintViolation<TestPricedRequest>> violations = validator.validate(req);

        assertThat(violations)
                .as("validation must fail when priceMax is provided without priceType")
                .isNotEmpty();
        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .as("the violation must reference the priceType field — it is the missing required input")
                .contains("priceType");
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static TestPricedRequest request(PriceType priceType, BigDecimal price,
                                              BigDecimal priceMin, BigDecimal priceMax) {
        return new TestPricedRequest(priceType, price, priceMin, priceMax);
    }

    @ServicePriceValid
    record TestPricedRequest(
            PriceType priceType,
            BigDecimal price,
            BigDecimal priceMin,
            BigDecimal priceMax
    ) implements PricedRequest {}
}
