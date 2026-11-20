package com.beautica.auth;

import com.beautica.config.InviteSmsPolicyConfig;
import com.beautica.user.InviteTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * B520 (D5, SAL-48) — daily retention sweep of staff phone invites: an UNACCEPTED invite (expired, cancelled or
 * superseded) is hard-deleted {@code app.invite.sms.retention} (30 days) after it expired or was revoked — the invited
 * phone is personal data of someone who never joined. Accepted invites (the joined staff member's own history) and
 * pre-B520 email rows are kept.
 *
 * <p>Mirrors {@link PasswordResetTokenCleanupJob}: one bounded {@code DELETE}, the cutoff derived from the injected
 * {@link Clock} (Anti-Bug §G). Nothing external to clean up: the row's only outbox reference is the already-sent
 * {@code INVITE} entry, whose aggregate id carries no FK.
 */
@Component
public class InviteTokenCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(InviteTokenCleanupJob.class);

    private final InviteTokenRepository inviteTokenRepository;
    private final InviteSmsPolicyConfig policy;
    private final Clock clock;

    public InviteTokenCleanupJob(InviteTokenRepository inviteTokenRepository, InviteSmsPolicyConfig policy,
                                 Clock clock) {
        this.inviteTokenRepository = inviteTokenRepository;
        this.policy = policy;
        this.clock = clock;
    }

    /** Daily at 03:52 UTC, offset from the other auth sweeps (03:17, 03:42). */
    @Scheduled(cron = "${app.invite.sms.cleanup-cron:0 52 3 * * *}", zone = "UTC")
    @Transactional
    public void sweepEndedPhoneInvites() {
        int deleted = sweep();
        if (deleted > 0) {
            log.info("Invite cleanup: deleted {} unaccepted phone invite row(s)", deleted);
        }
    }

    /**
     * Package-private seam so tests can run the statement with the pinned {@link Clock}.
     *
     * @return number of rows deleted
     */
    @Transactional
    int sweep() {
        Instant cutoff = clock.instant().minus(policy.retention());
        return inviteTokenRepository.deleteUnacceptedPhoneInvitesEndedBefore(cutoff);
    }
}
