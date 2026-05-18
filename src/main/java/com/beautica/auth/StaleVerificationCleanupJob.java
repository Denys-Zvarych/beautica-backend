package com.beautica.auth;

import com.beautica.config.VerificationPolicyConfig;
import com.beautica.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * Low-frequency sweep that nulls verification-code material on abandoned,
 * unverified registrations. Without it, {@code verification_code_hash} /
 * {@code verification_code_expires_at} linger forever on rows that were never
 * verified and never resent.
 *
 * <p>Runs a single bounded UPDATE (no per-row materialisation, no new index —
 * it rides the partial index added in V50). The retention window is a config
 * property; the cutoff is derived from the injected {@link Clock} so tests can
 * pin it (Anti-Bug Playbook §G — never bare {@code Instant.now()}).
 */
@Component
public class StaleVerificationCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(StaleVerificationCleanupJob.class);

    private final UserRepository userRepository;
    private final VerificationPolicyConfig verificationPolicy;
    private final Clock clock;

    public StaleVerificationCleanupJob(
            UserRepository userRepository,
            VerificationPolicyConfig verificationPolicy,
            Clock clock) {
        this.userRepository = userRepository;
        this.verificationPolicy = verificationPolicy;
        this.clock = clock;
    }

    /**
     * Daily at 03:17 UTC (off the top of the hour to avoid colliding with other
     * cron-aligned jobs). Cron is overridable for ops tuning / test pinning.
     */
    @Scheduled(cron = "${app.verification.stale-cleanup-cron:0 17 3 * * *}", zone = "UTC")
    @Transactional
    public void sweepStaleVerificationCodes() {
        int cleared = sweep();
        if (cleared > 0) {
            log.info("Stale verification cleanup: cleared {} abandoned unverified OTP row(s)", cleared);
        }
    }

    /**
     * Package-private seam so tests can invoke the bounded statement directly
     * with the pinned {@link Clock} without standing up the scheduler.
     *
     * @return number of rows whose verification code material was nulled
     */
    @Transactional
    int sweep() {
        Instant cutoff = clock.instant().minus(verificationPolicy.staleCodeRetention());
        return userRepository.nullifyStaleVerificationCodes(cutoff);
    }
}
