package com.beautica.service.entity;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for ServiceType entity defaults.
 * No Spring context — pure builder behaviour only.
 */
class ServiceTypeDefaultTest {

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();

    @AfterAll
    static void closeFactory() {
        FACTORY.close();
    }

    @Test
    @DisplayName("ServiceType.active defaults to true when built without an explicit value")
    void should_defaultActiveToTrue_when_builtWithoutExplicitActiveValue() {
        var type = ServiceType.builder()
                .nameUk("Манікюр класичний")
                .nameEn("Classic Manicure")
                .slug("manicure-classic-default-test")
                .build();

        assertThat(type.isActive())
                .as("ServiceType.active must default to true via @Builder.Default")
                .isTrue();
    }

    @Test
    @DisplayName("ServiceType.active is honoured as false when explicitly passed to the builder")
    void should_setActiveToFalse_when_explicitlyPassedFalse() {
        var type = ServiceType.builder()
                .nameUk("Застарілий тип")
                .nameEn("Deprecated Type")
                .slug("deprecated-type-default-test")
                .active(false)
                .build();

        assertThat(type.isActive())
                .as("ServiceType.active must honour an explicit false value from the builder")
                .isFalse();
    }

    @Test
    @DisplayName("should_violateSize_when_slugExceeds255Chars")
    void should_violateSize_when_slugExceeds255Chars() {
        var type = ServiceType.builder()
                .nameUk("Test")
                .slug("s".repeat(256))
                .build();

        var violations = FACTORY.getValidator().validate(type);

        assertThat(violations)
                .as("@Size(max=255) must fire when slug has 256 characters")
                .anyMatch(v -> v.getPropertyPath().toString().equals("slug"));
    }

    @Test
    @DisplayName("should_notViolateSize_when_slugHasExactly255Chars")
    void should_notViolateSize_when_slugHasExactly255Chars() {
        // 254 'a' chars + one '-b' suffix = 253 + 2 = 255 chars — max allowed
        var slug = "a".repeat(253) + "-b";
        var type = ServiceType.builder()
                .nameUk("Test")
                .slug(slug)
                .build();

        var sizeViolations = FACTORY.getValidator().validate(type)
                .stream()
                .filter(v -> v.getPropertyPath().toString().equals("slug")
                        && v.getConstraintDescriptor().getAnnotation().annotationType()
                                .equals(jakarta.validation.constraints.Size.class))
                .toList();

        assertThat(sizeViolations)
                .as("@Size(max=255) must NOT fire for exactly 255 characters")
                .isEmpty();
    }
}
