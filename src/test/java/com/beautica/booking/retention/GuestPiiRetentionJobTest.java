package com.beautica.booking.retention;

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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("GuestPiiRetentionJob — cutoff wiring, bean gating, property validation")
class GuestPiiRetentionJobTest {

    private static final Instant NOW = Instant.parse("2026-10-19T03:27:00Z");

    @Mock
    private GuestPiiAnonymisationService anonymisationService;

    @Test
    @DisplayName("passes now minus 12 calendar months (UTC) from the injected clock")
    void should_passNowMinusTwelveMonths_when_run() {
        GuestPiiRetentionJob job = new GuestPiiRetentionJob(
                anonymisationService, properties(12), Clock.fixed(NOW, ZoneOffset.UTC));

        job.run();

        verify(anonymisationService).anonymiseDue(Instant.parse("2025-10-19T03:27:00Z"));
    }

    @Test
    @DisplayName("uses calendar months: 29 Feb minus 12 months clamps to 28 Feb")
    void should_useCalendarMonths_when_dayDoesNotExistInTargetMonth() {
        GuestPiiRetentionJob job = new GuestPiiRetentionJob(
                anonymisationService, properties(12),
                Clock.fixed(Instant.parse("2028-02-29T03:27:00Z"), ZoneOffset.UTC));

        Instant cutoff = job.cutoff();

        assertThat(cutoff).isEqualTo(Instant.parse("2027-02-28T03:27:00Z"));
    }

    @Test
    @DisplayName("no job bean when app.guest-pii-retention.enabled=false; a bean when the property is missing")
    void should_gateJobBean_on_enabledProperty() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withBean(GuestPiiAnonymisationService.class, () -> mock(GuestPiiAnonymisationService.class))
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
