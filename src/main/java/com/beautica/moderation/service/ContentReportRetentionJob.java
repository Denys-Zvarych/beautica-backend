package com.beautica.moderation.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Daily D7 report-retention sweep (phase 396). Thin wrapper over {@link ContentReportRetentionService}, which does
 * the work in set-based statements. No ShedLock: both statements are idempotent, so two instances running at once
 * only repeat no-op work. 03:37 UTC is staggered from the 03:00–03:57 cleanup cluster. Absent when
 * {@code app.moderation.retention.enabled=false} (test profile).
 */
@Component
@ConditionalOnProperty(prefix = "app.moderation.retention", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class ContentReportRetentionJob {

    private final ContentReportRetentionService retentionService;
    private final Clock clock;

    public ContentReportRetentionJob(ContentReportRetentionService retentionService, Clock clock) {
        this.retentionService = retentionService;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.moderation.retention.cron:0 37 3 * * *}", zone = "UTC")
    public void run() {
        retentionService.sweep(clock.instant());
    }
}
