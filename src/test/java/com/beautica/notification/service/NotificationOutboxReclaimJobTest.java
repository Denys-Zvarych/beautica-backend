package com.beautica.notification.service;

import com.beautica.config.OutboxReclaimPolicyConfig;
import com.beautica.notification.repository.NotificationOutboxRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link NotificationOutboxReclaimJob} — the crash-safety half of the MEDIUM
 * concurrency fix (see {@link NotificationOutboxDrainWorker} javadoc).
 *
 * <p>Uses a fixed {@link Clock} (never {@code Instant.now()} directly — Anti-Bug Playbook §G)
 * so the {@code staleBefore} cutoff passed to the repository is asserted exactly, not "close to
 * now".
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationOutboxReclaimJob — unit")
class NotificationOutboxReclaimJobTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-07-14T12:00:00Z");
    private static final Duration THRESHOLD = Duration.ofMinutes(60);

    @Mock
    private NotificationOutboxRepository outboxRepository;

    private final Clock fixedClock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
    private final OutboxReclaimPolicyConfig policy = new OutboxReclaimPolicyConfig(THRESHOLD);

    private NotificationOutboxReclaimJob job() {
        return new NotificationOutboxReclaimJob(outboxRepository, policy, fixedClock);
    }

    @Test
    @DisplayName("sweep computes staleBefore as clock.instant() minus the configured threshold")
    void should_computeStaleBeforeFromClockMinusThreshold_when_sweepRuns() {
        when(outboxRepository.reclaimStaleProcessingRows(any(), anyInt(), anyString())).thenReturn(0);

        job().sweep();

        ArgumentCaptor<Instant> staleBeforeCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(outboxRepository).reclaimStaleProcessingRows(staleBeforeCaptor.capture(), anyInt(), anyString());
        assertThat(staleBeforeCaptor.getValue()).isEqualTo(FIXED_NOW.minus(THRESHOLD));
    }

    @Test
    @DisplayName("sweep passes NotificationOutboxDrainWorker.MAX_ATTEMPTS as the dead-letter ceiling")
    void should_passDrainWorkerMaxAttempts_when_sweepRuns() {
        when(outboxRepository.reclaimStaleProcessingRows(any(), anyInt(), anyString())).thenReturn(0);

        job().sweep();

        verify(outboxRepository).reclaimStaleProcessingRows(
                any(), eq(NotificationOutboxDrainWorker.MAX_ATTEMPTS), anyString());
    }

    @Test
    @DisplayName("sweep returns the reclaimed row count from the repository")
    void should_returnReclaimedCount_when_sweepRuns() {
        when(outboxRepository.reclaimStaleProcessingRows(any(), anyInt(), anyString())).thenReturn(3);

        int result = job().sweep();

        assertThat(result).isEqualTo(3);
    }

    @Test
    @DisplayName("reclaimStaleProcessingRows delegates to sweep and does not throw when zero rows are reclaimed")
    void should_notThrow_when_scheduledEntryPointRunsWithZeroReclaims() {
        when(outboxRepository.reclaimStaleProcessingRows(any(), anyInt(), anyString())).thenReturn(0);

        job().reclaimStaleProcessingRows();

        verify(outboxRepository).reclaimStaleProcessingRows(any(), anyInt(), anyString());
    }

    @Test
    @DisplayName("reclaimStaleProcessingRows delegates to sweep and does not throw when rows are reclaimed")
    void should_notThrow_when_scheduledEntryPointRunsWithReclaimedRows() {
        when(outboxRepository.reclaimStaleProcessingRows(any(), anyInt(), anyString())).thenReturn(5);

        job().reclaimStaleProcessingRows();

        verify(outboxRepository).reclaimStaleProcessingRows(any(), anyInt(), anyString());
    }

    private static Instant any() {
        return org.mockito.ArgumentMatchers.any(Instant.class);
    }
}
