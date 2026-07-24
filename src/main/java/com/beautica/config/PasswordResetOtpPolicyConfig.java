package com.beautica.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tunable policy knobs for the password-reset OTP anti-abuse machinery (Phase A2).
 *
 * <p>Mirrors {@link VerificationPolicyConfig} exactly (same knob shapes, same fail-fast
 * compact-constructor guards) — the password-reset OTP flow reuses
 * {@code EmailVerificationProcessor}'s locked critical section pattern verbatim via
 * {@code PasswordResetOtpProcessor}. Kept as a distinct {@code @ConfigurationProperties}
 * class (rather than folding these fields into {@link VerificationPolicyConfig}) so the two
 * flows can be tuned independently — e.g. a shorter {@code otpTtl} for password reset vs.
 * email verification — without one property change silently affecting the other feature.
 *
 * <p>{@code cumulativeFailureThreshold} / {@code lockoutDuration} back the resend-surviving
 * brute-force bound (a fresh OTP request rotates the code and resets the per-code
 * {@code maxAttemptsPerCode} window, but the lifetime failure counter is NOT reset, so an
 * attacker cannot loop request→guess indefinitely against the 1,000,000-value OTP space).
 *
 * <p>{@code resendCooldown} is the minimum time that must elapse between two consecutive
 * OTP requests for the same account — applies uniformly to both entry points
 * ({@code PasswordResetService#requestReset} and {@code #requestResetForUserId}) since both
 * funnel through the same {@code mintAndSendOtp} helper.
 *
 * <p>{@code ticketTtl} is the lifetime of the {@code PasswordResetTicket} minted by
 * {@code PasswordResetOtpProcessor} once the OTP has been verified — short (10 minutes by
 * default) because the caller has just proven account ownership and is expected to complete
 * the reset immediately, unlike the old 1-hour email-link TTL.
 */
@ConfigurationProperties(prefix = "app.password-reset-otp")
public record PasswordResetOtpPolicyConfig(
        Duration otpTtl,
        int maxAttemptsPerCode,
        int cumulativeFailureThreshold,
        Duration lockoutDuration,
        Duration resendCooldown,
        Duration ticketTtl
) {

    public PasswordResetOtpPolicyConfig {
        if (otpTtl == null || otpTtl.isNegative() || otpTtl.isZero()) {
            throw new IllegalStateException(
                    "app.password-reset-otp.otp-ttl must be a positive duration");
        }
        if (maxAttemptsPerCode < 1) {
            throw new IllegalStateException(
                    "app.password-reset-otp.max-attempts-per-code must be >= 1");
        }
        if (cumulativeFailureThreshold < 1) {
            throw new IllegalStateException(
                    "app.password-reset-otp.cumulative-failure-threshold must be >= 1");
        }
        if (lockoutDuration == null || lockoutDuration.isNegative() || lockoutDuration.isZero()) {
            throw new IllegalStateException(
                    "app.password-reset-otp.lockout-duration must be a positive duration");
        }
        if (resendCooldown == null || resendCooldown.isNegative() || resendCooldown.isZero()) {
            throw new IllegalStateException(
                    "app.password-reset-otp.resend-cooldown must be a positive duration");
        }
        if (ticketTtl == null || ticketTtl.isNegative() || ticketTtl.isZero()) {
            throw new IllegalStateException(
                    "app.password-reset-otp.ticket-ttl must be a positive duration");
        }
    }
}
