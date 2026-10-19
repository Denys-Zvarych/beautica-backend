package com.beautica.booking.retention;

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
 * 03:57 cleanup jobs. Absent when {@code app.guest-pii-retention.enabled=false} (test profile).
 */
@Component
@ConditionalOnProperty(prefix = "app.guest-pii-retention", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class GuestPiiRetentionJob {

    private final GuestPiiAnonymisationService anonymisationService;
    private final GuestPiiRetentionProperties properties;
    private final Clock clock;

    public GuestPiiRetentionJob(GuestPiiAnonymisationService anonymisationService,
                                GuestPiiRetentionProperties properties,
                                Clock clock) {
        this.anonymisationService = anonymisationService;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.guest-pii-retention.cron:0 27 3 * * *}", zone = "UTC")
    public void run() {
        anonymisationService.anonymiseDue(cutoff());
    }

    Instant cutoff() {
        return clock.instant().atOffset(ZoneOffset.UTC)
                .minusMonths(properties.retentionMonths())
                .toInstant();
    }
}
