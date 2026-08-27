package com.beautica.auth;

import com.beautica.config.RefreshTokenPolicyConfig;
import com.beautica.user.RefreshTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * Low-frequency sweep that hard-deletes expired {@code refresh_tokens} rows. Without it the
 * table grows unbounded: every rotation ({@code AuthService#refresh}) INSERTs a new row and
 * only REVOKES the predecessor (never deletes it — see
 * {@link RefreshTokenRepository#revokeAllByFamilyId}, which preserves the audit trail for
 * reuse-detection on purpose), and the only other cleanup is
 * {@link RefreshTokenRepository#deleteByUserId} on explicit logout, which mobile users
 * routinely never trigger. Going from a 7-day to a 30-day refresh-token TTL quadruples the
 * per-session accumulation window this sweep exists to bound.
 *
 * <p>Deletes only rows that are BOTH expired AND past {@code cleanupRetention} beyond expiry —
 * never an unexpired row, revoked or not. An expired token can never again pass
 * {@code AuthService#refresh}'s expiry check, so deletion past the retention window is lossless
 * for auth purposes; the retention buffer keeps recently-expired rows (including ones swept up
 * by reuse-detection family revocation) around briefly for incident / forensic lookup before
 * reclaiming the space.
 *
 * <p>Mirrors {@link PasswordResetTokenCleanupJob} / {@link StaleVerificationCleanupJob}: a
 * single bounded {@code DELETE} (no per-row materialisation), retention window from a config
 * property, and the cutoff derived from the injected {@link Clock} so tests can pin it
 * (Anti-Bug Playbook §G — never bare {@code Instant.now()}).
 */
@Component
public class RefreshTokenCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenCleanupJob.class);

    private final RefreshTokenRepository refreshTokenRepository;
    private final RefreshTokenPolicyConfig refreshTokenPolicy;
    private final Clock clock;

    public RefreshTokenCleanupJob(
            RefreshTokenRepository refreshTokenRepository,
            RefreshTokenPolicyConfig refreshTokenPolicy,
            Clock clock) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.refreshTokenPolicy = refreshTokenPolicy;
        this.clock = clock;
    }

    /**
     * Daily at 03:57 UTC — off the top of the hour, and offset from the other 03:xx cleanup
     * sweeps (outbox purge / phone-OTP at 03:00, verification at 03:17, password-reset at
     * 03:42) so none of them contend for the same window. Cron is overridable for ops
     * tuning / test pinning.
     */
    @Scheduled(cron = "${app.refresh-token.cleanup-cron:0 57 3 * * *}", zone = "UTC")
    @Transactional
    public void sweepExpiredRefreshTokens() {
        int deleted = sweep();
        if (deleted > 0) {
            log.info("Refresh token cleanup: deleted {} expired refresh_tokens row(s)", deleted);
        }
    }

    /**
     * Package-private seam so tests can invoke the bounded statement directly with the pinned
     * {@link Clock} without standing up the scheduler.
     *
     * @return number of expired refresh_tokens rows deleted
     */
    @Transactional
    int sweep() {
        Instant cutoff = clock.instant().minus(refreshTokenPolicy.cleanupRetention());
        return refreshTokenRepository.deleteAllByExpiresAtBefore(cutoff);
    }
}
