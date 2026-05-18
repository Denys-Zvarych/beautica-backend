package com.beautica.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fail-fast validation for the verification anti-abuse policy knobs.
 */
@DisplayName("VerificationPolicyConfig — fail-fast validation")
class VerificationPolicyConfigTest {

    @Test
    @DisplayName("should_throw_when_thresholdBelowOne")
    void should_throw_when_thresholdBelowOne() {
        assertThatThrownBy(() -> new VerificationPolicyConfig(0, Duration.ofMinutes(15), Duration.ofHours(24)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cumulative-failure-threshold");
    }

    @Test
    @DisplayName("should_throw_when_lockoutDurationNotPositive")
    void should_throw_when_lockoutDurationNotPositive() {
        assertThatThrownBy(() -> new VerificationPolicyConfig(10, Duration.ZERO, Duration.ofHours(24)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lockout-duration");
    }

    @Test
    @DisplayName("should_throw_when_staleRetentionNotPositive")
    void should_throw_when_staleRetentionNotPositive() {
        assertThatThrownBy(() -> new VerificationPolicyConfig(10, Duration.ofMinutes(15), Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stale-code-retention");
    }

    @Test
    @DisplayName("should_accept_when_allValuesValid")
    void should_accept_when_allValuesValid() {
        assertThatCode(() -> new VerificationPolicyConfig(10, Duration.ofMinutes(15), Duration.ofHours(24)))
                .doesNotThrowAnyException();
    }
}
