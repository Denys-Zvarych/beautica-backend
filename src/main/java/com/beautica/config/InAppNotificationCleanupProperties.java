package com.beautica.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Execution knobs for {@code InAppNotificationCleanupJob}'s daily retention sweep (phase 335) —
 * distinct from the retention WINDOW itself ({@link InAppNotificationRetentionProperties}, prefix
 * {@code notification.inapp}).
 *
 * <p>{@code enabled} is the kill switch, checked at the top of every scheduled run (not a
 * {@code @ConditionalOnProperty} bean gate) so the job bean always exists — the same reflection
 * probe and a plain Mockito unit test can therefore exercise "disabled flag -> no-op" without a
 * Spring context, and {@code AbstractIntegrationTest}'s test profile can flip it on for one class
 * via {@code @TestPropertySource} without a context reload driven by conditional bean creation.
 * Defaults to {@code true} in every profile except {@code test} ({@code application-test.yml}
 * turns it off, the same "quiet by default in a full @SpringBootTest context" posture already
 * used for {@code notification.outbox.drain}/{@code .reclaim}).
 *
 * <p>{@code batchSize} / {@code maxBatchesPerRun} bound {@code deleteCreatedBefore}'s loop —
 * {@code InAppNotificationRepository#deleteCreatedBefore} has no {@code LIMIT} of its own beyond
 * what is passed in, so an unbounded backlog is capped at {@code batchSize * maxBatchesPerRun}
 * rows deleted per run; a backlog larger than that is simply finished on the next day's run
 * (never lost — {@code created_at < cutoff} still matches it), trading one day of extra table
 * size for never holding a single very-long-running DELETE (and its row lock footprint) open.
 */
@ConfigurationProperties(prefix = "notification.inapp.cleanup")
public record InAppNotificationCleanupProperties(
        boolean enabled,
        int batchSize,
        int maxBatchesPerRun
) {

    /**
     * Upper bound on {@code batchSize} (audit-fix cycle 1, finding 3) — each batch runs inside its
     * own {@code REQUIRES_NEW} transaction ({@code InAppNotificationCleanupJob#deleteBatch}), so an
     * operator-supplied value with no ceiling could hold a single very long-running {@code DELETE}
     * (and its row-lock footprint) open, exactly the failure mode the whole batched-loop design
     * exists to avoid.
     */
    private static final int MAX_BATCH_SIZE = 10_000;

    /**
     * Upper bound on {@code maxBatchesPerRun} (audit-fix cycle 1, finding 3) — bounds the worst-case
     * per-run wall time ({@code batchSize * maxBatchesPerRun} rows) and the number of sequential
     * {@code REQUIRES_NEW} transactions one sweep can hold a scheduler thread for (Anti-Bug
     * Playbook §H-adjacent: the shared {@code TaskScheduler} pool is only 4 threads).
     */
    private static final int MAX_BATCHES_PER_RUN = 500;

    public InAppNotificationCleanupProperties {
        if (batchSize <= 0) {
            throw new IllegalStateException(
                    "notification.inapp.cleanup.batch-size must be positive (was " + batchSize + ")");
        }
        if (batchSize > MAX_BATCH_SIZE) {
            throw new IllegalStateException(
                    "notification.inapp.cleanup.batch-size must be at most " + MAX_BATCH_SIZE
                            + " (was " + batchSize + ")");
        }
        if (maxBatchesPerRun <= 0) {
            throw new IllegalStateException(
                    "notification.inapp.cleanup.max-batches-per-run must be positive (was "
                            + maxBatchesPerRun + ")");
        }
        if (maxBatchesPerRun > MAX_BATCHES_PER_RUN) {
            throw new IllegalStateException(
                    "notification.inapp.cleanup.max-batches-per-run must be at most "
                            + MAX_BATCHES_PER_RUN + " (was " + maxBatchesPerRun + ")");
        }
    }
}
