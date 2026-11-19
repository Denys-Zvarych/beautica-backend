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
 * {@code Instant.now()}). A row created more than 25 h ago is well past its 10-minute TTL, can no longer be
 * redeemed and has left the 24 h failed-verify window, so deletion is lossless.
 */
@Component
public class PhoneOtpCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(PhoneOtpCleanupJob.class);
    /**
     * By {@code created_at}, 25 h (phone-first hardening, AUTH-D26 c): the per-phone failed-verify cap sums the rows of
     * the last 24 h, so a row must outlive that window even when the 03:00 sweep runs right after it.
     */
    static final Duration RETENTION = Duration.ofHours(25);

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
        // V258 failure log rides the same 25 h retention, so the per-(phone, network) 24 h window is never cut short.
        phoneOtpRepository.deleteFailuresBefore(cutoff);
        return phoneOtpRepository.deleteByCreatedAtBefore(cutoff);
    }
}
