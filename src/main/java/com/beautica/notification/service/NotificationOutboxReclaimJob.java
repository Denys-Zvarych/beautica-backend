package com.beautica.notification.service;

import com.beautica.config.OutboxReclaimPolicyConfig;
import com.beautica.notification.repository.NotificationOutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * Low-frequency sweep that rescues {@code notification_outbox} rows stranded in
 * {@code PROCESSING} by an instance that claimed them (via
 * {@link com.beautica.notification.repository.NotificationOutboxRepository#claimPendingBatch(int)})
 * and then crashed or was killed before {@link NotificationOutboxDrainWorker} could record a
 * dispatch outcome — most commonly an old instance being torn down mid-dispatch during a
 * Railway rolling deploy.
 *
 * <p>Split out as its own {@code @Component} — mirroring {@code StaleVerificationCleanupJob} /
 * {@code PasswordResetTokenCleanupJob} — rather than folded into
 * {@link NotificationOutboxDrainWorker}: it is a distinct responsibility (crash recovery, not
 * the claim/dispatch/persist pipeline) on its own independent schedule, and
 * {@code NotificationOutboxDrainWorker} is already at the class-length budget.
 *
 * <p>A single bounded {@code UPDATE} (no per-row materialisation) — see
 * {@link NotificationOutboxRepository#reclaimStaleProcessingRows(Instant, int, String)} for the
 * exact predicate and the DEAD-vs-PENDING branching. The retention window is a config property;
 * the cutoff is derived from the injected {@link Clock} so tests can pin it (Anti-Bug Playbook
 * §G — never bare {@code Instant.now()}).
 */
@Component
public class NotificationOutboxReclaimJob {

    private static final Logger log = LoggerFactory.getLogger(NotificationOutboxReclaimJob.class);

    private static final String RECLAIM_MESSAGE =
            "Reclaimed after stale PROCESSING claim (possible instance crash mid-dispatch)";

    private final NotificationOutboxRepository outboxRepository;
    private final OutboxReclaimPolicyConfig reclaimPolicy;
    private final Clock clock;

    public NotificationOutboxReclaimJob(
            NotificationOutboxRepository outboxRepository,
            OutboxReclaimPolicyConfig reclaimPolicy,
            Clock clock) {
        this.outboxRepository = outboxRepository;
        this.reclaimPolicy = reclaimPolicy;
        this.clock = clock;
    }

    /**
     * Every 5 minutes by default (property-driven so ops can tune it, or tests can pin an
     * effectively-never-fires interval the same way
     * {@code notification.outbox.drain.fixed-delay-ms} does for
     * {@link NotificationOutboxDrainWorker#drain()}).
     */
    @Scheduled(fixedDelayString = "${notification.outbox.reclaim.fixed-delay-ms:300000}")
    public void reclaimStaleProcessingRows() {
        int reclaimed = sweep();
        if (reclaimed > 0) {
            log.warn("Outbox reclaim: reset {} stale PROCESSING row(s) stranded since before the "
                    + "{} threshold (possible instance crash mid-dispatch)",
                    reclaimed, reclaimPolicy.staleClaimThreshold());
        }
    }

    /**
     * Package-private seam so tests can invoke the bounded statement directly with the pinned
     * {@link Clock} without standing up the scheduler.
     *
     * @return number of rows reclaimed
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    int sweep() {
        Instant staleBefore = clock.instant().minus(reclaimPolicy.staleClaimThreshold());
        return outboxRepository.reclaimStaleProcessingRows(staleBefore, NotificationOutboxDrainWorker.MAX_ATTEMPTS, RECLAIM_MESSAGE);
    }
}
