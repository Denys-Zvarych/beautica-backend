package com.beautica.auth;

import com.beautica.config.PasswordResetPolicyConfig;
import com.beautica.user.PasswordResetTicketRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * Low-frequency sweep that hard-deletes expired password-reset tickets. Without it the
 * {@code password_reset_tickets} side table grows unbounded — one row per successful OTP
 * verification, never reclaimed.
 *
 * <p>Class name retained as {@code PasswordResetTokenCleanupJob} across the Phase A1
 * entity/table rename ({@code PasswordResetToken} → {@code PasswordResetTicket}) — only its
 * repository dependency changed type; the scheduling contract and public shape are unchanged.
 *
 * <p>Mirrors {@link StaleVerificationCleanupJob}: a single bounded {@code DELETE} (no per-row
 * materialisation), retention window from a config property, and the cutoff derived from the
 * injected {@link Clock} so tests can pin it (Anti-Bug Playbook §G — never bare
 * {@code Instant.now()}). A ticket expired beyond the retention window can no longer be
 * redeemed (TTL + single-use enforced at confirm time), so deletion is lossless.
 */
@Component
public class PasswordResetTokenCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetTokenCleanupJob.class);

    private final PasswordResetTicketRepository passwordResetTicketRepository;
    private final PasswordResetPolicyConfig passwordResetPolicy;
    private final Clock clock;

    public PasswordResetTokenCleanupJob(
            PasswordResetTicketRepository passwordResetTicketRepository,
            PasswordResetPolicyConfig passwordResetPolicy,
            Clock clock) {
        this.passwordResetTicketRepository = passwordResetTicketRepository;
        this.passwordResetPolicy = passwordResetPolicy;
        this.clock = clock;
    }

    /**
     * Daily at 03:42 UTC (off the top of the hour, and offset from the verification sweep at
     * 03:17 so the two never contend). Cron is overridable for ops tuning / test pinning.
     */
    @Scheduled(cron = "${app.password-reset.cleanup-cron:0 42 3 * * *}", zone = "UTC")
    @Transactional
    public void sweepExpiredResetTokens() {
        int deleted = sweep();
        if (deleted > 0) {
            log.info("Password-reset ticket cleanup: deleted {} expired ticket row(s)", deleted);
        }
    }

    /**
     * Package-private seam so tests can invoke the bounded statement directly with the
     * pinned {@link Clock} without standing up the scheduler.
     *
     * @return number of expired ticket rows deleted
     */
    @Transactional
    int sweep() {
        Instant cutoff = clock.instant().minus(passwordResetPolicy.cleanupRetention());
        return passwordResetTicketRepository.deleteByExpiresAtBefore(cutoff);
    }
}
