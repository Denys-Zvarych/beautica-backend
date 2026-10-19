package com.beautica.booking.retention;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Guest-PII retention settings (phase 373 D1/D7), bound from {@code app.guest-pii-retention}.
 *
 * <p>Validated fail-fast at bind time, mirroring {@code InAppNotificationCleanupProperties}.
 * {@code retentionMonths} can never drop below the 12 months the privacy policy promises — in any
 * profile, tests included (they pass a cutoff directly instead of shortening the window).
 *
 * @param enabled          creates the scheduled job bean when {@code true} (test profile: false)
 * @param cron             Spring cron for the job, UTC
 * @param retentionMonths  calendar months after {@code ends_at} before a row is anonymised
 * @param batchSize        rows per claim statement / transaction
 * @param maxBatchesPerRun per-run budget; the remainder is picked up next run
 */
@ConfigurationProperties(prefix = "app.guest-pii-retention")
public record GuestPiiRetentionProperties(
        boolean enabled,
        String cron,
        int retentionMonths,
        int batchSize,
        int maxBatchesPerRun
) {

    static final int MIN_RETENTION_MONTHS = 12;
    private static final int MAX_BATCH_SIZE = 10_000;
    private static final int MAX_BATCHES_PER_RUN = 1_000;

    public GuestPiiRetentionProperties {
        if (retentionMonths < MIN_RETENTION_MONTHS) {
            throw new IllegalStateException("app.guest-pii-retention.retention-months must be >= "
                    + MIN_RETENTION_MONTHS + " (was " + retentionMonths + ")");
        }
        if (batchSize <= 0 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalStateException("app.guest-pii-retention.batch-size must be in [1, "
                    + MAX_BATCH_SIZE + "] (was " + batchSize + ")");
        }
        if (maxBatchesPerRun <= 0 || maxBatchesPerRun > MAX_BATCHES_PER_RUN) {
            throw new IllegalStateException("app.guest-pii-retention.max-batches-per-run must be in [1, "
                    + MAX_BATCHES_PER_RUN + "] (was " + maxBatchesPerRun + ")");
        }
    }
}
