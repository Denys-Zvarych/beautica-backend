package com.beautica.location.entity;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests that verify {@code @Size(max = N)} constraints on the three
 * KATOTTH reference entities ({@link Oblast}, {@link City}, {@link CityDistrict}).
 *
 * <p>These entities have no HTTP DTO layer — validation lives on the entity itself
 * so that the Phase 10.2 static-factory seed path surfaces a
 * {@code ConstraintViolationException} (clean 500 with a useful message) instead
 * of a raw JDBC driver 500 on a DB column overflow.
 *
 * <p>Uses the Jakarta Validation API directly — no Spring context needed (§M slice
 * tests &gt; full {@code @SpringBootTest}).
 */
@DisplayName("Location entity Bean Validation — @Size constraints")
class LocationEntityValidationTest {

    private static Validator validator;

    @BeforeAll
    static void buildValidator() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    // ── Oblast ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Oblast")
    class OblastTests {

        @Test
        @DisplayName("should_passValidation_when_allFieldsWithinBounds")
        void should_passValidation_when_allFieldsWithinBounds() {
            Oblast oblast = Oblast.of("UA80000000000093317", "Київська область", "Kyiv Oblast");

            Set<ConstraintViolation<Oblast>> violations = validator.validate(oblast);

            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("should_violateSize_when_katotthCodeExceedsMax")
        void should_violateSize_when_katotthCodeExceedsMax() {
            // 21 characters — one over the @Column(length=20) / @Size(max=20) limit
            Oblast oblast = Oblast.of("UA800000000000933170X", "Київська область", "Kyiv Oblast");

            Set<ConstraintViolation<Oblast>> violations = validator.validate(oblast);

            assertThat(violations)
                    .hasSize(1)
                    .extracting(v -> v.getPropertyPath().toString())
                    .containsExactly("katotthCode");
        }

        @Test
        @DisplayName("should_violateSize_when_nameUkExceedsMax")
        void should_violateSize_when_nameUkExceedsMax() {
            Oblast oblast = Oblast.of("UA80000000000093317", "а".repeat(256), "Kyiv Oblast");

            Set<ConstraintViolation<Oblast>> violations = validator.validate(oblast);

            assertThat(violations)
                    .hasSize(1)
                    .extracting(v -> v.getPropertyPath().toString())
                    .containsExactly("nameUk");
        }

        @Test
        @DisplayName("should_violateSize_when_nameEnExceedsMax")
        void should_violateSize_when_nameEnExceedsMax() {
            Oblast oblast = Oblast.of("UA80000000000093317", "Київська область", "x".repeat(256));

            Set<ConstraintViolation<Oblast>> violations = validator.validate(oblast);

            assertThat(violations)
                    .hasSize(1)
                    .extracting(v -> v.getPropertyPath().toString())
                    .containsExactly("nameEn");
        }
    }

    // ── City ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("City")
    class CityTests {

        private final Oblast oblast = Oblast.of("UA80000000000093317", "Київська область", "Kyiv Oblast");

        @Test
        @DisplayName("should_passValidation_when_allFieldsWithinBounds")
        void should_passValidation_when_allFieldsWithinBounds() {
            City city = City.of(oblast, "UA80000000000093317", "Київ", "Kyiv");

            Set<ConstraintViolation<City>> violations = validator.validate(city);

            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("should_violateSize_when_katotthCodeExceedsMax")
        void should_violateSize_when_katotthCodeExceedsMax() {
            // 21 characters — one over the limit
            City city = City.of(oblast, "UA800000000000933170X", "Київ", "Kyiv");

            Set<ConstraintViolation<City>> violations = validator.validate(city);

            assertThat(violations)
                    .hasSize(1)
                    .extracting(v -> v.getPropertyPath().toString())
                    .containsExactly("katotthCode");
        }

        @Test
        @DisplayName("should_violateSize_when_nameUkExceedsMax")
        void should_violateSize_when_nameUkExceedsMax() {
            City city = City.of(oblast, "UA80000000000093317", "а".repeat(256), "Kyiv");

            Set<ConstraintViolation<City>> violations = validator.validate(city);

            assertThat(violations)
                    .hasSize(1)
                    .extracting(v -> v.getPropertyPath().toString())
                    .containsExactly("nameUk");
        }

        @Test
        @DisplayName("should_violateSize_when_nameEnExceedsMax")
        void should_violateSize_when_nameEnExceedsMax() {
            City city = City.of(oblast, "UA80000000000093317", "Київ", "x".repeat(256));

            Set<ConstraintViolation<City>> violations = validator.validate(city);

            assertThat(violations)
                    .hasSize(1)
                    .extracting(v -> v.getPropertyPath().toString())
                    .containsExactly("nameEn");
        }
    }

    // ── CityDistrict ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("CityDistrict")
    class CityDistrictTests {

        private final Oblast oblast = Oblast.of("UA80000000000093317", "Київська область", "Kyiv Oblast");
        private final City city = City.of(oblast, "UA80000000000093317", "Київ", "Kyiv");

        @Test
        @DisplayName("should_passValidation_when_allFieldsWithinBounds")
        void should_passValidation_when_allFieldsWithinBounds() {
            CityDistrict district = CityDistrict.of(city, "UA80000000001078669",
                    "Голосіївський район", "Holosiivskyi district");

            Set<ConstraintViolation<CityDistrict>> violations = validator.validate(district);

            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("should_violateSize_when_katotthCodeExceedsMax")
        void should_violateSize_when_katotthCodeExceedsMax() {
            // 21 characters — one over the limit
            CityDistrict district = CityDistrict.of(city, "UA800000000010786690X",
                    "Голосіївський район", "Holosiivskyi district");

            Set<ConstraintViolation<CityDistrict>> violations = validator.validate(district);

            assertThat(violations)
                    .hasSize(1)
                    .extracting(v -> v.getPropertyPath().toString())
                    .containsExactly("katotthCode");
        }

        @Test
        @DisplayName("should_violateSize_when_nameUkExceedsMax")
        void should_violateSize_when_nameUkExceedsMax() {
            CityDistrict district = CityDistrict.of(city, "UA80000000001078669",
                    "а".repeat(256), "Holosiivskyi district");

            Set<ConstraintViolation<CityDistrict>> violations = validator.validate(district);

            assertThat(violations)
                    .hasSize(1)
                    .extracting(v -> v.getPropertyPath().toString())
                    .containsExactly("nameUk");
        }

        @Test
        @DisplayName("should_violateSize_when_nameEnExceedsMax")
        void should_violateSize_when_nameEnExceedsMax() {
            CityDistrict district = CityDistrict.of(city, "UA80000000001078669",
                    "Голосіївський район", "x".repeat(256));

            Set<ConstraintViolation<CityDistrict>> violations = validator.validate(district);

            assertThat(violations)
                    .hasSize(1)
                    .extracting(v -> v.getPropertyPath().toString())
                    .containsExactly("nameEn");
        }
    }
}
