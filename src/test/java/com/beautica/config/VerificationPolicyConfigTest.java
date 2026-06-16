package com.beautica.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fail-fast validation for the verification anti-abuse policy knobs.
 *
 * <p>The compact constructor rejects four bad-input shapes per {@link Duration}
 * knob: {@code null}, {@link Duration#isNegative() negative}, and
 * {@link Duration#isZero() zero}; the threshold rejects {@code < 1}. Each branch
 * below is pinned independently so a future relaxation of any single guard
 * (e.g. dropping the {@code isNegative()} check) surfaces as a failing test
 * rather than silently admitting a non-positive duration into production.
 */
@DisplayName("VerificationPolicyConfig — fail-fast validation")
class VerificationPolicyConfigTest {

    // Valid baseline values reused while varying one knob at a time.
    private static final int VALID_THRESHOLD = 10;
    private static final Duration VALID_LOCKOUT = Duration.ofMinutes(15);
    private static final Duration VALID_STALE = Duration.ofHours(24);
    private static final Duration VALID_COOLDOWN = Duration.ofSeconds(60);

    @Test
    @DisplayName("threshold below 1 is rejected as IllegalStateException")
    void should_rejectThreshold_when_belowOne() {
        assertThatThrownBy(() -> new VerificationPolicyConfig(
                0, VALID_LOCKOUT, VALID_STALE, VALID_COOLDOWN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cumulative-failure-threshold");
    }

    @Nested
    @DisplayName("lockoutDuration must be a positive duration")
    class LockoutDuration {

        @Test
        @DisplayName("null lockoutDuration is rejected")
        void should_rejectLockout_when_null() {
            assertThatThrownBy(() -> new VerificationPolicyConfig(
                    VALID_THRESHOLD, null, VALID_STALE, VALID_COOLDOWN))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("lockout-duration");
        }

        @Test
        @DisplayName("negative lockoutDuration is rejected (isNegative branch)")
        void should_rejectLockout_when_negative() {
            assertThatThrownBy(() -> new VerificationPolicyConfig(
                    VALID_THRESHOLD, Duration.ofSeconds(-1), VALID_STALE, VALID_COOLDOWN))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("lockout-duration");
        }

        @Test
        @DisplayName("zero lockoutDuration is rejected (isZero branch)")
        void should_rejectLockout_when_zero() {
            assertThatThrownBy(() -> new VerificationPolicyConfig(
                    VALID_THRESHOLD, Duration.ZERO, VALID_STALE, VALID_COOLDOWN))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("lockout-duration");
        }
    }

    @Nested
    @DisplayName("staleCodeRetention must be a positive duration")
    class StaleCodeRetention {

        @Test
        @DisplayName("null staleCodeRetention is rejected")
        void should_rejectStaleRetention_when_null() {
            assertThatThrownBy(() -> new VerificationPolicyConfig(
                    VALID_THRESHOLD, VALID_LOCKOUT, null, VALID_COOLDOWN))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("stale-code-retention");
        }

        @Test
        @DisplayName("negative staleCodeRetention is rejected (isNegative branch)")
        void should_rejectStaleRetention_when_negative() {
            assertThatThrownBy(() -> new VerificationPolicyConfig(
                    VALID_THRESHOLD, VALID_LOCKOUT, Duration.ofSeconds(-1), VALID_COOLDOWN))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("stale-code-retention");
        }

        @Test
        @DisplayName("zero staleCodeRetention is rejected (isZero branch)")
        void should_rejectStaleRetention_when_zero() {
            assertThatThrownBy(() -> new VerificationPolicyConfig(
                    VALID_THRESHOLD, VALID_LOCKOUT, Duration.ZERO, VALID_COOLDOWN))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("stale-code-retention");
        }
    }

    @Nested
    @DisplayName("resendCooldown must be a positive duration")
    class ResendCooldown {

        @Test
        @DisplayName("null resendCooldown is rejected")
        void should_rejectCooldown_when_null() {
            assertThatThrownBy(() -> new VerificationPolicyConfig(
                    VALID_THRESHOLD, VALID_LOCKOUT, VALID_STALE, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("resend-cooldown");
        }

        @Test
        @DisplayName("negative resendCooldown is rejected (isNegative branch)")
        void should_rejectCooldown_when_negative() {
            assertThatThrownBy(() -> new VerificationPolicyConfig(
                    VALID_THRESHOLD, VALID_LOCKOUT, VALID_STALE, Duration.ofSeconds(-1)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("resend-cooldown");
        }

        @Test
        @DisplayName("zero resendCooldown is rejected (isZero branch)")
        void should_rejectCooldown_when_zero() {
            assertThatThrownBy(() -> new VerificationPolicyConfig(
                    VALID_THRESHOLD, VALID_LOCKOUT, VALID_STALE, Duration.ZERO))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("resend-cooldown");
        }
    }

    @Test
    @DisplayName("all-positive values are accepted without throwing")
    void should_accept_when_allValuesValid() {
        assertThatCode(() -> new VerificationPolicyConfig(
                VALID_THRESHOLD, VALID_LOCKOUT, VALID_STALE, VALID_COOLDOWN))
                .doesNotThrowAnyException();
    }
}
