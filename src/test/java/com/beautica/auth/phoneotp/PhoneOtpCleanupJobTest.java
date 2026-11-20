package com.beautica.auth.phoneotp;

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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link PhoneOtpCleanupJob}: the cutoff is derived from the injected
 * {@link Clock} (§G), and the bounded DELETE deletes only rows older than the 24h
 * retention window — the stubbed repo models "2 expired deleted, 1 fresh kept".
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PhoneOtpCleanupJob — unit")
class PhoneOtpCleanupJobTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-06-17T03:00:00Z");
    /** Phone-first hardening: by created_at, 25 h, so the 24 h failed-verify window survives the 03:00 sweep. */
    private static final Duration RETENTION = Duration.ofHours(25);

    @Mock
    private PhoneOtpRepository phoneOtpRepository;

    @Test
    @DisplayName("should_deleteRowsOlderThanRetention_when_sweepRuns")
    void should_deleteRowsOlderThanRetention_when_sweepRuns() {
        Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        var job = new PhoneOtpCleanupJob(phoneOtpRepository, clock);
        // 2 of the 3 rows were created before now-25h; the 1 fresh row is not matched by the cutoff.
        when(phoneOtpRepository.deleteByCreatedAtBefore(FIXED_NOW.minus(RETENTION))).thenReturn(2);

        int deleted = job.sweep();

        assertThat(deleted).as("only the 2 expired rows are deleted; the fresh row is kept").isEqualTo(2);
        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(phoneOtpRepository).deleteByCreatedAtBefore(cutoff.capture());
        assertThat(cutoff.getValue())
                .as("cutoff must be exactly clock.now - 25h retention")
                .isEqualTo(FIXED_NOW.minus(RETENTION));
    }

    @Test
    @DisplayName("should_notFail_when_noExpiredRows")
    void should_notFail_when_noExpiredRows() {
        Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        var job = new PhoneOtpCleanupJob(phoneOtpRepository, clock);
        when(phoneOtpRepository.deleteByCreatedAtBefore(FIXED_NOW.minus(RETENTION))).thenReturn(0);

        job.sweepExpiredOtps();

        verify(phoneOtpRepository).deleteByCreatedAtBefore(FIXED_NOW.minus(RETENTION));
    }

    @Test
    @DisplayName("should_keepTheFullFailedVerifyWindow_when_retentionCompared")
    void should_keepTheFullFailedVerifyWindow_when_retentionCompared() {
        assertThat(PhoneOtpCleanupJob.RETENTION)
                .as("rows must outlive the 24 h per-phone failed-verify window (AUTH-D26 c)")
                .isEqualTo(RETENTION)
                .isGreaterThan(Duration.ofHours(24));
    }

    @Test
    @DisplayName("should_sweepFailureLogWithSameCutoff_when_sweepRuns")
    void should_sweepFailureLogWithSameCutoff_when_sweepRuns() {
        var job = new PhoneOtpCleanupJob(phoneOtpRepository, Clock.fixed(FIXED_NOW, ZoneOffset.UTC));

        job.sweep();

        verify(phoneOtpRepository).deleteFailuresBefore(FIXED_NOW.minus(RETENTION));
    }
}
