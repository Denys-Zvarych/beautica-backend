package com.beautica.booking.retention;

import com.beautica.review.invite.service.GuestReviewRetentionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("GuestPiiRetentionJob — cutoff wiring, bean gating, property validation")
class GuestPiiRetentionJobTest {

    private static final Instant NOW = Instant.parse("2026-10-19T03:27:00Z");

    @Mock
    private GuestPiiAnonymisationService anonymisationService;

    @Mock
    private GuestReviewRetentionService guestReviewRetentionService;

    @Test
    @DisplayName("passes now minus 12 calendar months (UTC) from the injected clock")
    void should_passNowMinusTwelveMonths_when_run() {
        GuestPiiRetentionJob job = new GuestPiiRetentionJob(
                anonymisationService, guestReviewRetentionService, properties(12), Clock.fixed(NOW, ZoneOffset.UTC));

        job.run();

        verify(anonymisationService).anonymiseDue(Instant.parse("2025-10-19T03:27:00Z"));
    }

    @Test
    @DisplayName("phase 402 D3: purges review invites at the clock's now with the retention batch settings")
    void should_purgeInvitesWithBatchSettings_when_run() {
        GuestPiiRetentionJob job = new GuestPiiRetentionJob(
                anonymisationService, guestReviewRetentionService, properties(12), Clock.fixed(NOW, ZoneOffset.UTC));

        job.run();

        verify(guestReviewRetentionService).purgeInvites(NOW, 500, 200);
    }

    @Test
    @DisplayName("phase 402 D3: the invite purge still runs when the anonymisation throws, which propagates")
    void should_purgeInvites_when_anonymisationThrows() {
        when(anonymisationService.anonymiseDue(any())).thenThrow(new IllegalStateException("db down"));
        GuestPiiRetentionJob job = new GuestPiiRetentionJob(
                anonymisationService, guestReviewRetentionService, properties(12), Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(job::run).isInstanceOf(IllegalStateException.class).hasMessage("db down");

        verify(guestReviewRetentionService).purgeInvites(NOW, 500, 200);
    }

    @Test
    @DisplayName("phase 402 D3: a purge failure is logged, never thrown out of the job")
    void should_swallowPurgeFailure_when_purgeThrows() {
        doThrow(new IllegalStateException("purge")).when(guestReviewRetentionService)
                .purgeInvites(any(), anyInt(), anyInt());
        GuestPiiRetentionJob job = new GuestPiiRetentionJob(
                anonymisationService, guestReviewRetentionService, properties(12), Clock.fixed(NOW, ZoneOffset.UTC));

        job.run();

        verify(anonymisationService).anonymiseDue(Instant.parse("2025-10-19T03:27:00Z"));
    }

    @Test
    @DisplayName("uses calendar months: 29 Feb minus 12 months clamps to 28 Feb")
    void should_useCalendarMonths_when_dayDoesNotExistInTargetMonth() {
        GuestPiiRetentionJob job = new GuestPiiRetentionJob(
                anonymisationService, guestReviewRetentionService, properties(12),
                Clock.fixed(Instant.parse("2028-02-29T03:27:00Z"), ZoneOffset.UTC));

        Instant cutoff = job.cutoff();

        assertThat(cutoff).isEqualTo(Instant.parse("2027-02-28T03:27:00Z"));
    }

    @Test
    @DisplayName("no job bean when app.guest-pii-retention.enabled=false; a bean when the property is missing")
    void should_gateJobBean_on_enabledProperty() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withBean(GuestPiiAnonymisationService.class, () -> mock(GuestPiiAnonymisationService.class))
                .withBean(GuestReviewRetentionService.class, () -> mock(GuestReviewRetentionService.class))
                .withBean(GuestPiiRetentionProperties.class, () -> properties(12))
                .withBean(Clock.class, Clock::systemUTC)
                .withUserConfiguration(GuestPiiRetentionJob.class);

        runner.withPropertyValues("app.guest-pii-retention.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(GuestPiiRetentionJob.class));
        runner.run(ctx -> assertThat(ctx).hasSingleBean(GuestPiiRetentionJob.class));
    }

    @Test
    @DisplayName("rejects a retention window below the 12 months the privacy policy promises")
    void should_rejectRetention_when_belowTwelveMonths() {
        assertThatThrownBy(() -> properties(11))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("retention-months");
    }

    @Test
    @DisplayName("rejects a non-positive batch size and batch budget")
    void should_rejectBatchSettings_when_nonPositive() {
        assertThatThrownBy(() -> new GuestPiiRetentionProperties(true, "0 27 3 * * *", 12, 0, 200))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new GuestPiiRetentionProperties(true, "0 27 3 * * *", 12, 500, 0))
                .isInstanceOf(IllegalStateException.class);
    }

    private static GuestPiiRetentionProperties properties(int months) {
        return new GuestPiiRetentionProperties(true, "0 27 3 * * *", months, 500, 200);
    }
}
