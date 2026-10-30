package com.beautica.booking.retention;

import com.beautica.review.invite.service.GuestReviewRetentionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Nightly guest-PII retention sweep (phase 373 D7). Thin wrapper: computes the cutoff from the
 * injected {@link Clock} ({@code now − retentionMonths} calendar months, UTC) and delegates.
 *
 * <p>No ShedLock: multi-instance safety is the {@code FOR UPDATE SKIP LOCKED} claim inside
 * {@link GuestPiiAnonymisationService}. 03:27 UTC is staggered from the 03:00 / 03:17 / 03:42 /
 * 03:57 cleanup jobs. Phase 402 D3: the same run then purges terminal review invites
 * ({@link GuestReviewRetentionService#purgeInvites}, same batch size and per-run budget): {@code SKIPPED} /
 * {@code FAILED} 30 days after creation (can be earlier than their terminal moment), {@code SENT}-past-expiry /
 * {@code EXPIRED} / {@code CONSUMED} 30 days after expiry / consume. The purge runs even
 * when the anonymisation throws, and a purge failure is logged, never thrown over it. Absent when {@code app.guest-pii-retention.enabled=false} (test profile).
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.guest-pii-retention", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class GuestPiiRetentionJob {

    private final GuestPiiAnonymisationService anonymisationService;
    private final GuestReviewRetentionService guestReviewRetentionService;
    private final GuestPiiRetentionProperties properties;
    private final Clock clock;

    public GuestPiiRetentionJob(GuestPiiAnonymisationService anonymisationService,
                                GuestReviewRetentionService guestReviewRetentionService,
                                GuestPiiRetentionProperties properties,
                                Clock clock) {
        this.anonymisationService = anonymisationService;
        this.guestReviewRetentionService = guestReviewRetentionService;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.guest-pii-retention.cron:0 27 3 * * *}", zone = "UTC")
    public void run() {
        try {
            anonymisationService.anonymiseDue(cutoff());
        } finally {
            purgeInvites();
        }
    }

    /** Logged, never thrown: it runs in a {@code finally} and must not replace an anonymisation failure. */
    private void purgeInvites() {
        try {
            guestReviewRetentionService.purgeInvites(
                    clock.instant(), properties.batchSize(), properties.maxBatchesPerRun());
        } catch (RuntimeException e) {
            log.error("Review invite purge failed: {}", e.getClass().getSimpleName());
        }
    }

    Instant cutoff() {
        return clock.instant().atOffset(ZoneOffset.UTC)
                .minusMonths(properties.retentionMonths())
                .toInstant();
    }
}
