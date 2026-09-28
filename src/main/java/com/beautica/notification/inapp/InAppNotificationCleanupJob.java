package com.beautica.notification.inapp;

import com.beautica.config.InAppNotificationCleanupProperties;
import com.beautica.config.InAppNotificationRetentionProperties;
import com.beautica.notification.inapp.repository.InAppNotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Retention sweep for the in-app notification feed (phase 335) — bounds {@code
 * in_app_notification} table growth by hard-deleting rows (read or unread) older than {@link
 * InAppNotificationRetentionProperties#retentionDays()} (default 90).
 *
 * <p><b>Schedule.</b> Daily at 04:12 UTC by default — deliberately clear of the existing
 * 03:00-03:57 UTC cleanup cluster ({@code NotificationOutboxDrainWorker#purgeStaleOutboxRows} at
 * 03:00, {@code StaleVerificationCleanupJob} at 03:17, {@code PasswordResetTokenCleanupJob} at
 * 03:42, {@code RefreshTokenCleanupJob} at 03:57), so this sweep never contends with any of them
 * for the same window. Cron is property-driven ({@code notification.inapp.cleanup.cron}) for ops
 * tuning / test pinning, mirroring every other cleanup job in this codebase.
 *
 * <p><b>Bounded, batched delete — the one thing that makes this job different from its
 * one-statement siblings.</b> {@code in_app_notification} can accumulate far more rows than
 * {@code refresh_tokens}/{@code password_reset_tickets} ever do (one row per recipient per
 * booking/review/invite event, phase 333), so a single unbounded {@code DELETE ... WHERE
 * created_at < cutoff} risks a very long-running statement holding row locks across the whole
 * backlog. Each batch is deleted via {@link #deleteBatch(Instant, int)}, called through the {@code
 * self} proxy so its {@code REQUIRES_NEW} annotation is honoured (see that field's javadoc) — the
 * exact three-phase-drain idiom {@code NotificationOutboxDrainWorker#claimBatch}/{@code
 * #persistOne} already established for "loop calling a REQUIRES_NEW batch method through a
 * self-proxy". The loop stops when a batch returns fewer rows than {@code batchSize} (the backlog
 * is exhausted) or {@code maxBatchesPerRun} is reached (this run's budget is spent — the remainder
 * is picked up by tomorrow's run; a row is never "lost", only deleted a day later than it could
 * have been).
 *
 * <p><b>No distributed lock (ShedLock or otherwise).</b> Matches every other {@code *CleanupJob}
 * in this codebase: {@code deleteCreatedBefore} is a plain bounded {@code DELETE}, idempotent and
 * safe to run concurrently from two Railway instances during a rolling deploy — at worst two
 * instances each claim a distinct (or overlapping, PostgreSQL-serialised) slice of the same
 * backlog, and neither can ever delete a row that should have been kept.
 *
 * <p><b>{@link Clock}-derived cutoff</b> (Anti-Bug Playbook §G) — never bare {@code
 * Instant.now()}, so tests can pin it.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InAppNotificationCleanupJob {

    private final InAppNotificationRepository repository;
    private final InAppNotificationCleanupProperties cleanupProperties;
    private final InAppNotificationRetentionProperties retentionProperties;
    private final Clock clock;

    /**
     * Self-proxy reference so {@link #sweep()}'s loop calls {@link #deleteBatch(Instant, int)}
     * through the Spring AOP proxy and its {@code @Transactional(REQUIRES_NEW)} is honoured — a
     * direct {@code this.deleteBatch(...)} call bypasses the proxy entirely (self-invocation does
     * not trigger AOP). Mirrors {@code NotificationOutboxDrainWorker#self}'s exact javadoc
     * rationale: this is a deliberate, documented exception to the project's no-field-injection
     * rule, since a self-proxy cannot be expressed as a constructor parameter (circular dependency
     * at construction time).
     */
    @Autowired
    @Lazy
    private InAppNotificationCleanupJob self;

    @Scheduled(cron = "${notification.inapp.cleanup.cron:0 12 4 * * *}", zone = "UTC")
    public void sweep() {
        if (!cleanupProperties.enabled()) {
            log.debug("in-app notification retention sweep skipped "
                    + "(notification.inapp.cleanup.enabled=false)");
            return;
        }

        Instant startedAt = clock.instant();
        Instant cutoff = computeCutoff();
        int batchSize = cleanupProperties.batchSize();
        int maxBatches = cleanupProperties.maxBatchesPerRun();

        int totalDeleted = 0;
        int batchesRun = 0;
        int deletedThisBatch;
        do {
            deletedThisBatch = self.deleteBatch(cutoff, batchSize);
            totalDeleted += deletedThisBatch;
            batchesRun++;
        } while (deletedThisBatch == batchSize && batchesRun < maxBatches);

        long elapsedMs = Duration.between(startedAt, clock.instant()).toMillis();
        log.info("in-app notification retention sweep: deleted={} batches={} cutoff={} elapsedMs={}",
                totalDeleted, batchesRun, cutoff, elapsedMs);

        if (batchesRun == maxBatches) {
            // The loop hit its per-run budget rather than exhausting the backlog on its own — the
            // remainder is picked up by tomorrow's run (never lost, see class javadoc), but this is
            // still worth a WARN so it's visible in Railway logs: a backlog that never finishes
            // draining across days means batchSize/maxBatchesPerRun need retuning. Counts only, no
            // PII (Anti-Bug §I).
            log.warn("in-app notification retention sweep: backlog not fully drained this run "
                            + "(batchesRun={} maxBatchesPerRun={} batchSize={} deleted={}); "
                            + "remainder will be picked up by tomorrow's run",
                    batchesRun, maxBatches, batchSize, totalDeleted);
        }
    }

    /**
     * Package-private seam so tests can assert the cutoff arithmetic directly, without standing
     * up the scheduler or a batch loop.
     */
    Instant computeCutoff() {
        return clock.instant().minus(Duration.ofDays(retentionProperties.retentionDays()));
    }

    /**
     * One bounded batch, its own short {@code REQUIRES_NEW} transaction — see the class javadoc
     * for why this must be called through {@link #self}, never directly.
     *
     * @return the number of rows this batch actually deleted (less than {@code limit} exactly
     *         when the backlog older than {@code cutoff} is exhausted)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deleteBatch(Instant cutoff, int limit) {
        return repository.deleteCreatedBefore(cutoff, limit);
    }
}
