package com.beautica.service.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for ServiceType entity defaults.
 * No Spring context — pure builder behaviour only.
 */
class ServiceTypeDefaultTest {

    @Test
    @DisplayName("should_defaultActiveToTrue_when_builtWithoutExplicitActiveValue")
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
    @DisplayName("should_setActiveToFalse_when_explicitlyPassedFalse")
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
}
