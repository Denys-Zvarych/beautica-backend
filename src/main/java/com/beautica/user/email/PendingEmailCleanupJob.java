package com.beautica.user.email;

import com.beautica.config.VerificationPolicyConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * B516 — daily sweep of abandoned notifications-email requests older than {@code app.verification.stale-code-retention}
 * (24 h), so an address the user never confirmed is not retained (mirrors {@code StaleVerificationCleanupJob}).
 */
@Component
public class PendingEmailCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(PendingEmailCleanupJob.class);

    private final PendingEmailRepository pendingEmailRepository;
    private final VerificationPolicyConfig verificationPolicy;
    private final Clock clock;

    public PendingEmailCleanupJob(PendingEmailRepository pendingEmailRepository,
                                  VerificationPolicyConfig verificationPolicy,
                                  Clock clock) {
        this.pendingEmailRepository = pendingEmailRepository;
        this.verificationPolicy = verificationPolicy;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.verification.pending-email-cleanup-cron:0 27 3 * * *}", zone = "UTC")
    @Transactional
    public void sweep() {
        int deleted = pendingEmailRepository.deleteAbandonedBefore(
                clock.instant().minus(verificationPolicy.staleCodeRetention()));
        if (deleted > 0) {
            log.info("Pending email cleanup: deleted {} abandoned request(s)", deleted);
        }
    }
}
