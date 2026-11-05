package com.beautica.notification.telegram.link;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * Phase 409 D4 — pins the public guarantee of privacy policy 1.4: threshold + longest gap between runs ≤ 2 days.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TelegramLinkTokenPurgeJob — 2-day purge promise (phase 409 D4)")
class TelegramLinkTokenPurgeJobTest {

    private static final Duration POLICY_MAX = Duration.ofDays(2);
    private static final Instant NOW = Instant.parse("2026-10-31T03:41:00Z");

    @Mock private TelegramLinkRetentionService retentionService;
    @Mock private TelegramLinkOrphanBackstop backstop;

    @Test
    @DisplayName("hourly: expiry grace + the longest gap between two runs over a year (UTC, leap year) = 1 day + 1 h "
            + "≤ 2 days")
    void should_keepTwoDayPromise_when_cronAndThresholdCombined() throws Exception {
        Scheduled scheduled = TelegramLinkTokenPurgeJob.class.getMethod("run").getAnnotation(Scheduled.class);
        CronExpression cron = CronExpression.parse(scheduled.cron());
        ZonedDateTime run = cron.next(ZonedDateTime.parse("2027-12-31T00:00:00Z"));
        Duration maxGap = Duration.ZERO;

        for (int i = 0; i < 366 * 24 + 10; i++) {
            ZonedDateTime next = cron.next(run);
            Duration gap = Duration.between(run, next);
            maxGap = gap.compareTo(maxGap) > 0 ? gap : maxGap;
            run = next;
        }

        assertThat(scheduled.cron()).isEqualTo("0 41 * * * *");
        assertThat(scheduled.zone()).isEqualTo("UTC");
        assertThat(maxGap).isEqualTo(Duration.ofHours(1));
        assertThat(TelegramLinkTokenPurgeJob.EXPIRY_GRACE.plus(maxGap)).isLessThanOrEqualTo(POLICY_MAX);
    }

    @Test
    @DisplayName("run purges tokens expired before now − 1 day, batch 1000 × 100")
    void should_purgeWithOneDayCutoffAndBatch1000_when_run() {
        TelegramLinkTokenPurgeJob job = new TelegramLinkTokenPurgeJob(retentionService, backstop,
                Clock.fixed(NOW, ZoneOffset.UTC));

        job.run();

        verify(retentionService).purgeExpiredTokens(NOW.minus(Duration.ofDays(1)), 1_000, 100);
    }

    @Test
    @DisplayName("weekly orphan backstop runs Sundays 03:51 UTC at the clock's now")
    void should_runBackstopWeekly_when_sweepOrphanLinks() throws Exception {
        Scheduled scheduled = TelegramLinkTokenPurgeJob.class.getMethod("sweepOrphanLinks")
                .getAnnotation(Scheduled.class);
        TelegramLinkTokenPurgeJob job = new TelegramLinkTokenPurgeJob(retentionService, backstop,
                Clock.fixed(NOW, ZoneOffset.UTC));

        job.sweepOrphanLinks();

        assertThat(scheduled.cron()).isEqualTo("0 51 3 * * SUN");
        assertThat(scheduled.zone()).isEqualTo("UTC");
        verify(backstop).deleteOrphanLinks(NOW);
    }

    @Test
    @DisplayName("no disable flag: the bean is gated only by @Profile(\"!test\")")
    void should_haveNoConditionalFlag_when_inspected() {
        assertThat(TelegramLinkTokenPurgeJob.class.getAnnotation(
                org.springframework.boot.autoconfigure.condition.ConditionalOnProperty.class)).isNull();
        assertThat(TelegramLinkTokenPurgeJob.class.getAnnotation(org.springframework.context.annotation.Profile.class)
                .value()).containsExactly("!test");
    }
}
