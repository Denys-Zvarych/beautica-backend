package com.beautica.notification.inapp;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.beautica.config.InAppNotificationCleanupProperties;
import com.beautica.config.InAppNotificationRetentionProperties;
import com.beautica.notification.inapp.repository.InAppNotificationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link InAppNotificationCleanupJob} (phase 335).
 *
 * <p>Uses a fixed {@link Clock} (Anti-Bug Playbook §G — never bare {@code Instant.now()}) so the
 * cutoff is asserted exactly, not "close to now". The batching/budget-cap/real-delete behaviour is
 * covered over a real Postgres by {@code InAppNotificationCleanupJobIT}; this class covers the
 * pieces a plain Mockito test can pin cheaply: the cutoff arithmetic, the disabled-flag no-op, and
 * the scheduled cron/zone wiring.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InAppNotificationCleanupJob — unit")
class InAppNotificationCleanupJobTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-09-28T04:12:00Z");

    @Mock
    private InAppNotificationRepository repository;

    private InAppNotificationCleanupJob job(InAppNotificationCleanupProperties cleanupProperties,
                                             InAppNotificationRetentionProperties retentionProperties) {
        Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        InAppNotificationCleanupJob job = new InAppNotificationCleanupJob(
                repository, cleanupProperties, retentionProperties, clock);
        // The `self` field is a @Lazy @Autowired self-proxy reference used so sweep() calls
        // deleteBatch() through the Spring AOP proxy. In a plain Mockito unit test there is no
        // proxy, so point `self` directly at the job instance (mirrors
        // NotificationOutboxDrainWorkerTest#wireself's exact technique).
        ReflectionTestUtils.setField(job, "self", job);
        return job;
    }

    // ── cutoff arithmetic ───────────────────────────────────────────────────

    @Test
    @DisplayName("computeCutoff returns exactly clock.instant() minus the configured retention window")
    void should_computeCutoffFromClockMinusRetentionDays_when_retentionDaysIs90() {
        InAppNotificationCleanupJob job = job(
                new InAppNotificationCleanupProperties(true, 1000, 50),
                new InAppNotificationRetentionProperties(90));

        Instant cutoff = job.computeCutoff();

        assertThat(cutoff).isEqualTo(FIXED_NOW.minus(Duration.ofDays(90)));
    }

    @Test
    @DisplayName("computeCutoff tracks a non-default retentionDays value")
    void should_computeCutoffFromClockMinusRetentionDays_when_retentionDaysIsCustom() {
        InAppNotificationCleanupJob job = job(
                new InAppNotificationCleanupProperties(true, 1000, 50),
                new InAppNotificationRetentionProperties(30));

        Instant cutoff = job.computeCutoff();

        assertThat(cutoff).isEqualTo(FIXED_NOW.minus(Duration.ofDays(30)));
    }

    // ── disabled flag ───────────────────────────────────────────────────────

    @Test
    @DisplayName("sweep is a no-op and never touches the repository when notification.inapp.cleanup.enabled=false")
    void should_notTouchRepository_when_disabled() {
        InAppNotificationCleanupJob job = job(
                new InAppNotificationCleanupProperties(false, 1000, 50),
                new InAppNotificationRetentionProperties(90));

        job.sweep();

        verifyNoInteractions(repository);
    }

    // ── enabled: single-batch and multi-batch loop shape ───────────────────

    @Test
    @DisplayName("sweep stops after one batch when the backlog is smaller than batchSize")
    void should_stopAfterOneBatch_when_backlogSmallerThanBatchSize() {
        InAppNotificationCleanupJob job = job(
                new InAppNotificationCleanupProperties(true, 1000, 50),
                new InAppNotificationRetentionProperties(90));
        when(repository.deleteCreatedBefore(any(Instant.class), eq(1000))).thenReturn(3);

        job.sweep();

        verify(repository).deleteCreatedBefore(any(Instant.class), eq(1000));
    }

    @Test
    @DisplayName("sweep calls deleteBatch with the cutoff derived from the fixed clock")
    void should_passClockDerivedCutoff_when_sweepRuns() {
        InAppNotificationCleanupJob job = job(
                new InAppNotificationCleanupProperties(true, 1000, 50),
                new InAppNotificationRetentionProperties(90));
        when(repository.deleteCreatedBefore(any(Instant.class), anyInt())).thenReturn(0);

        job.sweep();

        verify(repository).deleteCreatedBefore(eq(FIXED_NOW.minus(Duration.ofDays(90))), eq(1000));
    }

    // ── budget-cap backlog WARN (audit-fix cycle 1, finding 2) ──────────────

    @Test
    @DisplayName("sweep logs a WARN when the run stops because batchesRun == maxBatchesPerRun "
            + "(the backlog is not fully drained this run)")
    void should_logWarn_when_runStopsAtMaxBatchesPerRun() {
        InAppNotificationCleanupJob job = job(
                new InAppNotificationCleanupProperties(true, 5, 2),
                new InAppNotificationRetentionProperties(90));
        when(repository.deleteCreatedBefore(any(Instant.class), eq(5))).thenReturn(5);

        Logger jobLogger = (Logger) LoggerFactory.getLogger(InAppNotificationCleanupJob.class);
        ListAppender<ILoggingEvent> logAppender = new ListAppender<>();
        logAppender.start();
        jobLogger.addAppender(logAppender);
        jobLogger.setLevel(Level.WARN);
        try {
            job.sweep();
        } finally {
            jobLogger.detachAppender(logAppender);
        }

        verify(repository, times(2)).deleteCreatedBefore(any(Instant.class), eq(5));
        assertThat(logAppender.list)
                .as("a WARN must be logged when batchesRun reaches maxBatchesPerRun")
                .anyMatch(event -> event.getLevel() == Level.WARN
                        && event.getFormattedMessage().contains("backlog not fully drained"));
    }

    @Test
    @DisplayName("sweep logs no WARN when the backlog is exhausted before maxBatchesPerRun is reached")
    void should_notLogWarn_when_backlogExhaustedBeforeMaxBatches() {
        InAppNotificationCleanupJob job = job(
                new InAppNotificationCleanupProperties(true, 5, 50),
                new InAppNotificationRetentionProperties(90));
        when(repository.deleteCreatedBefore(any(Instant.class), eq(5))).thenReturn(3);

        Logger jobLogger = (Logger) LoggerFactory.getLogger(InAppNotificationCleanupJob.class);
        ListAppender<ILoggingEvent> logAppender = new ListAppender<>();
        logAppender.start();
        jobLogger.addAppender(logAppender);
        jobLogger.setLevel(Level.WARN);
        try {
            job.sweep();
        } finally {
            jobLogger.detachAppender(logAppender);
        }

        assertThat(logAppender.list)
                .as("no WARN when the backlog finishes on its own, well under the per-run budget")
                .noneMatch(event -> event.getLevel() == Level.WARN);
    }

    // ── scheduled cron/zone wiring ──────────────────────────────────────────

    @Test
    @DisplayName("sweep() is @Scheduled daily at 04:12 UTC by default, off the existing 03:00-03:57 "
            + "cleanup cluster, with the cron itself property-overridable")
    void should_useDaily0412UtcCron_when_annotationInspected() throws NoSuchMethodException {
        Scheduled scheduled = InAppNotificationCleanupJob.class.getMethod("sweep").getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.cron()).isEqualTo("${notification.inapp.cleanup.cron:0 12 4 * * *}");
        assertThat(scheduled.zone()).isEqualTo("UTC");
    }
}
