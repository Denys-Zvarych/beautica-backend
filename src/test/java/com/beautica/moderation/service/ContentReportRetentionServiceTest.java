package com.beautica.moderation.service;

import com.beautica.moderation.config.ModerationProperties;
import com.beautica.moderation.repository.ContentReportRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ContentReportRetentionService / Job — unit (phase 396 D7)")
class ContentReportRetentionServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-28T03:37:00.123456789Z");
    private static final Instant NOW_MICROS = Instant.parse("2026-10-28T03:37:00.123456Z");

    private static final Instant CUTOFF = Instant.parse("2025-10-28T03:37:00.123456Z");
    private static final int BATCH = 2;
    private static final int MAX_BATCHES = 3;

    @Mock
    private ContentReportRepository repository;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("sweep: every target type's gone-target statement, each batched until a short batch, then the purge "
            + "(cut off one calendar year back) batched within the per-run budget")
    void should_markEveryTypeInBatchesThenPurge_when_swept() {
        when(repository.markGoneReviewTargets(NOW_MICROS, BATCH)).thenReturn(2, 2, 1);
        when(repository.markGoneClientReviewTargets(NOW_MICROS, BATCH)).thenReturn(0);
        when(repository.markGonePortfolioPhotoTargets(NOW_MICROS, BATCH)).thenReturn(1);
        when(repository.markGoneMasterProfileTargets(NOW_MICROS, BATCH)).thenReturn(0);
        when(repository.markGoneSalonProfileTargets(NOW_MICROS, BATCH)).thenReturn(0);
        when(repository.markGoneClientProfileTargets(NOW_MICROS, BATCH)).thenReturn(0);
        when(repository.deleteGoneBefore(CUTOFF, BATCH)).thenReturn(2);

        ContentReportRetentionService.SweepResult result = service().sweep(NOW);

        assertThat(result).as("5 + 1 marked; purge stops at the 3-batch budget").isEqualTo(
                new ContentReportRetentionService.SweepResult(6, 6));
        verify(repository, times(3)).markGoneReviewTargets(NOW_MICROS, BATCH);
        verify(repository, times(MAX_BATCHES)).deleteGoneBefore(CUTOFF, BATCH);
        var order = inOrder(repository);
        order.verify(repository, times(3)).markGoneReviewTargets(NOW_MICROS, BATCH);
        order.verify(repository).markGoneClientProfileTargets(NOW_MICROS, BATCH);
        order.verify(repository, times(MAX_BATCHES)).deleteGoneBefore(CUTOFF, BATCH);
    }

    @Test
    @DisplayName("each batch runs in its own transaction (REQUIRES_NEW), committed one by one")
    void should_commitEachBatch_when_swept() {
        when(repository.markGoneReviewTargets(NOW_MICROS, BATCH)).thenReturn(2, 0);

        service().sweep(NOW);

        // review: 2 batches; 5 other types: 1 each; purge: 1 → 8 transactions.
        verify(transactionManager, times(8)).commit(any());
    }

    private ContentReportRetentionService service() {
        ModerationProperties properties = new ModerationProperties(3, 5, Duration.ofMinutes(10), 1,
                Duration.ofHours(24), new ModerationProperties.Retention(false, "0 37 3 * * *", BATCH, MAX_BATCHES));
        return new ContentReportRetentionService(repository, properties, transactionManager);
    }

    @Test
    @DisplayName("cutoff is one calendar year back in UTC (leap day → 28 Feb)")
    void should_subtractOneCalendarYear_when_cutoffComputed() {
        assertThat(ContentReportRetentionService.cutoff(Instant.parse("2028-02-29T00:00:00Z")))
                .isEqualTo(Instant.parse("2027-02-28T00:00:00Z"));
    }

    @Test
    @DisplayName("job delegates with the injected clock's instant")
    void should_sweepAtClockInstant_when_jobRuns() {
        ContentReportRetentionService service = mock(ContentReportRetentionService.class);
        ContentReportRetentionJob job = new ContentReportRetentionJob(service, Clock.fixed(NOW, ZoneOffset.UTC));

        job.run();

        verify(service).sweep(NOW);
    }
}
