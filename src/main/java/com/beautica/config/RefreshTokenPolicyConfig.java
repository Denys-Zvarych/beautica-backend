package com.beautica.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tunable policy knobs for the refresh-token cleanup sweep.
 *
 * <p>{@code cleanupRetention} is the age past expiry after which a stale
 * {@code refresh_tokens} row is hard-deleted by the daily sweep
 * ({@code RefreshTokenCleanupJob}). Mirrors {@link PasswordResetPolicyConfig}'s
 * {@code cleanupRetention} pattern. An expired refresh token can never again pass
 * {@code AuthService#refresh}'s expiry check, so deletion past this window is lossless for
 * auth purposes — the retention buffer only keeps recently-expired rows (including ones
 * revoked by reuse-detection family revocation) around briefly for incident / forensic
 * lookup before reclaiming the space.
 *
 * <p>Distinct from the token's own lifetime ({@code app.jwt.refresh-token-expiration},
 * 30 days by default) — that is the TTL used when minting a token; this is how long the
 * now-useless row lingers in the table afterward. Without this sweep, every rotation
 * inserts a row and only revokes (never deletes) siblings — see
 * {@code RefreshTokenRepository#revokeAllByFamilyId} — so {@code refresh_tokens} only grows;
 * going from a 7-day to a 30-day TTL quadruples the per-session accumulation window.
 */
@ConfigurationProperties(prefix = "app.refresh-token")
public record RefreshTokenPolicyConfig(
        Duration cleanupRetention
) {

    public RefreshTokenPolicyConfig {
        if (cleanupRetention == null || cleanupRetention.isNegative() || cleanupRetention.isZero()) {
            throw new IllegalStateException(
                    "app.refresh-token.cleanup-retention must be a positive duration");
        }
    }
}
