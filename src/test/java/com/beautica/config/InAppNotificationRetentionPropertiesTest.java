package com.beautica.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fail-fast validation for the in-app notification retention window (phase 335). Mirrors {@code
 * VerificationPolicyConfigTest}'s compact-constructor pinning style.
 */
@DisplayName("InAppNotificationRetentionProperties — fail-fast validation")
class InAppNotificationRetentionPropertiesTest {

    @Test
    @DisplayName("should_rejectRetentionOutsideBounds — below the 7-day floor")
    void should_rejectRetentionOutsideBounds_when_belowFloor() {
        assertThatThrownBy(() -> new InAppNotificationRetentionProperties(6))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("retention-days");
    }

    @Test
    @DisplayName("should_rejectRetentionOutsideBounds — above the 365-day ceiling")
    void should_rejectRetentionOutsideBounds_when_aboveCeiling() {
        assertThatThrownBy(() -> new InAppNotificationRetentionProperties(366))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("retention-days");
    }

    @Test
    @DisplayName("boundary values 7 and 365 are both accepted")
    void should_accept_when_valueIsExactlyAtEitherBoundary() {
        assertThatCode(() -> new InAppNotificationRetentionProperties(7)).doesNotThrowAnyException();
        assertThatCode(() -> new InAppNotificationRetentionProperties(365)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the production default (90) is accepted")
    void should_accept_when_valueIsProductionDefault() {
        assertThatCode(() -> new InAppNotificationRetentionProperties(90)).doesNotThrowAnyException();
    }
}
