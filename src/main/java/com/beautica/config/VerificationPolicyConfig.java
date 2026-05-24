package com.beautica.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tunable policy knobs for the email-verification anti-abuse machinery.
 *
 * <p>{@code cumulativeFailureThreshold} / {@code lockoutDuration} back the
 * resend-surviving brute-force bound (a resend rotates the OTP and resets the
 * per-OTP 5-attempt window, but the lifetime failure counter is NOT reset, so
 * an attacker cannot loop resend→guess indefinitely against the 1,000,000-value
 * space). {@code staleCodeRetention} is the age after which an abandoned,
 * unverified OTP row is nulled by the scheduled sweep.
 *
 * <p>{@code resendCooldown} is the minimum time that must elapse between two
 * consecutive OTP resend requests for the same account. Production default is
 * 60 s; the local profile overrides to 10 s so developers don't have to wait.
 */
@ConfigurationProperties(prefix = "app.verification")
public record VerificationPolicyConfig(
        int cumulativeFailureThreshold,
        Duration lockoutDuration,
        Duration staleCodeRetention,
        Duration resendCooldown
) {

    public VerificationPolicyConfig {
        if (cumulativeFailureThreshold < 1) {
            throw new IllegalStateException(
                    "app.verification.cumulative-failure-threshold must be >= 1");
        }
        if (lockoutDuration == null || lockoutDuration.isNegative() || lockoutDuration.isZero()) {
            throw new IllegalStateException(
                    "app.verification.lockout-duration must be a positive duration");
        }
        if (staleCodeRetention == null || staleCodeRetention.isNegative() || staleCodeRetention.isZero()) {
            throw new IllegalStateException(
                    "app.verification.stale-code-retention must be a positive duration");
        }
        if (resendCooldown == null || resendCooldown.isNegative() || resendCooldown.isZero()) {
            throw new IllegalStateException(
                    "app.verification.resend-cooldown must be a positive duration");
        }
    }
}
