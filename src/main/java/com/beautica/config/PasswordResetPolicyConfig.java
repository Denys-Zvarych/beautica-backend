package com.beautica.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tunable policy knobs for the password-reset token lifecycle.
 *
 * <p>{@code cleanupRetention} is the age past expiry after which a stale
 * {@code password_reset_tokens} row is hard-deleted by the daily sweep
 * ({@code PasswordResetTokenCleanupJob}). Mirrors {@link VerificationPolicyConfig}'s
 * {@code staleCodeRetention} pattern. A token expired beyond this window can no longer be
 * redeemed (TTL + single-use are enforced at confirm time), so deletion is lossless — the
 * retention buffer simply keeps recently-expired rows around briefly for any forensic /
 * support lookup before reclaiming the space.
 *
 * <p>Note: the raw-token TTL itself ({@code app.password-reset.token-expiration-hours},
 * default 1 h) is read via {@code @Value} in {@code PasswordResetService} and is independent
 * of this retention window.
 */
@ConfigurationProperties(prefix = "app.password-reset")
public record PasswordResetPolicyConfig(
        Duration cleanupRetention
) {

    public PasswordResetPolicyConfig {
        if (cleanupRetention == null || cleanupRetention.isNegative() || cleanupRetention.isZero()) {
            throw new IllegalStateException(
                    "app.password-reset.cleanup-retention must be a positive duration");
        }
    }
}
