package com.beautica.moderation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Phase 396 moderation settings, bound from {@code app.moderation}. Validated fail-fast at bind time.
 *
 * @param autoHideThreshold  distinct eligible reporters that hide a review (D2, locked: 3)
 * @param burstThreshold     OPEN eligible review reports on one provider within {@code burstWindow} that trip the burst
 *                           guard
 * @param burstWindow        the burst guard's sliding window
 * @param autoHideCap        auto-hides allowed per provider within {@code autoHideCapWindow} (audit-fix D16: 1); a
 *                           further threshold hit holds the review's reports for support and flags the e-mail
 * @param autoHideCapWindow  the cap's sliding window (24 h)
 * @param retention          D7 retention job switch, schedule and batching
 */
@ConfigurationProperties(prefix = "app.moderation")
public record ModerationProperties(
        int autoHideThreshold,
        int burstThreshold,
        Duration burstWindow,
        int autoHideCap,
        Duration autoHideCapWindow,
        Retention retention
) {

    private static final int MAX_THRESHOLD = 1_000;
    private static final int MAX_BATCH = 10_000;

    public ModerationProperties {
        requireInRange("auto-hide-threshold", autoHideThreshold, MAX_THRESHOLD);
        requireInRange("burst-threshold", burstThreshold, MAX_THRESHOLD);
        requireInRange("auto-hide-cap", autoHideCap, MAX_THRESHOLD);
        requirePositive("burst-window", burstWindow);
        requirePositive("auto-hide-cap-window", autoHideCapWindow);
        if (retention == null) {
            throw new IllegalStateException("app.moderation.retention must be configured");
        }
    }

    private static void requireInRange(String name, int value, int max) {
        if (value < 1 || value > max) {
            throw new IllegalStateException("app.moderation." + name + " must be in [1, " + max
                    + "] (was " + value + ")");
        }
    }

    private static void requirePositive(String name, Duration value) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalStateException("app.moderation." + name + " must be positive (was " + value + ")");
        }
    }

    /**
     * @param enabled          creates the scheduled {@code ContentReportRetentionJob} bean (test profile: false)
     * @param cron             Spring cron for the job, UTC
     * @param batchSize        rows per gone-target / purge statement (same default as guest-pii-retention: 500)
     * @param maxBatchesPerRun batch budget per statement kind per run (same default as guest-pii-retention: 200)
     */
    public record Retention(boolean enabled, String cron, int batchSize, int maxBatchesPerRun) {

        public Retention {
            requireInRange("retention.batch-size", batchSize, MAX_BATCH);
            requireInRange("retention.max-batches-per-run", maxBatchesPerRun, MAX_BATCH);
        }
    }
}
