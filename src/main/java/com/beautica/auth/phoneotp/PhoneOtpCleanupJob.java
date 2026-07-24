package com.beautica.auth.phoneotp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Low-frequency sweep that hard-deletes spent {@code phone_otps} rows older than the
 * retention window, so the side table does not grow unbounded (one row per OTP send,
 * never reclaimed otherwise).
 *
 * <p>Mirrors {@code PasswordResetTokenCleanupJob} / {@code StaleVerificationCleanupJob}:
 * a single bounded {@code DELETE} (no per-row materialisation) and the cutoff derived
 * from the injected {@link Clock} so tests can pin it (Anti-Bug Playbook §G — never bare
 * {@code Instant.now()}). An OTP older than 24h is well past its 10-minute TTL and can
 * no longer be redeemed, so deletion is lossless.
 */
@Component
public class PhoneOtpCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(PhoneOtpCleanupJob.class);
    private static final Duration RETENTION = Duration.ofHours(24);

    private final PhoneOtpRepository phoneOtpRepository;
    private final Clock clock;

    public PhoneOtpCleanupJob(PhoneOtpRepository phoneOtpRepository, Clock clock) {
        this.phoneOtpRepository = phoneOtpRepository;
        this.clock = clock;
    }

    /** Daily at 03:00 UTC. Cron overridable for ops tuning / test pinning. */
    @Scheduled(cron = "${app.phone-otp.cleanup-cron:0 0 3 * * *}", zone = "UTC")
    @Transactional
    public void sweepExpiredOtps() {
        int deleted = sweep();
        if (deleted > 0) {
            log.info("Phone-OTP cleanup: deleted {} expired OTP row(s)", deleted);
        }
    }

    /**
     * Package-private seam so tests can invoke the bounded statement directly with the
     * pinned {@link Clock} without standing up the scheduler.
     *
     * @return number of expired OTP rows deleted
     */
    @Transactional
    int sweep() {
        Instant cutoff = clock.instant().minus(RETENTION);
        return phoneOtpRepository.deleteByExpiresAtBefore(cutoff);
    }
}
