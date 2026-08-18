package com.beautica.booking.closure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Phase 29.6 unit tests. The bean-absence/presence assertions use Spring Boot's {@link
 * ApplicationContextRunner} rather than a full {@code @SpringBootTest} — per Anti-Bug §M1, loading
 * Tomcat + Testcontainers Postgres purely to assert a {@code @ConditionalOnProperty} bean's
 * presence would be the exact "Clock bean assertion" overkill that rule warns against. A
 * full-context confirmation still exists — see {@code
 * ClosureReminderJobIT#should_exposeJobBean_when_enabledTrueInRealSpringContext} — this class
 * covers the fast, isolated cases.
 */
@DisplayName("ClosureReminderJob — unit")
class ClosureReminderJobTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2031-06-15T12:00:00Z");

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(Clock.class, () -> Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC))
            .withBean(ClosureReminderClaimService.class, () -> mock(ClosureReminderClaimService.class));

    // -------------------------------------------------------------------------
    // Kill switch — bean presence/absence
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("job bean is absent when booking.closure-reminder.enabled is unset (the default)")
    void should_haveNoJobBean_when_enabledPropertyAbsent() {
        contextRunner
                .withBean(ClosureReminderProperties.class, ClosureReminderProperties::new)
                .withUserConfiguration(ClosureReminderJob.class)
                .run(context -> assertThat(context).doesNotHaveBean(ClosureReminderJob.class));
    }

    @Test
    @DisplayName("job bean is absent when booking.closure-reminder.enabled=false")
    void should_haveNoJobBean_when_enabledPropertyFalse() {
        contextRunner
                .withPropertyValues("booking.closure-reminder.enabled=false")
                .withBean(ClosureReminderProperties.class, ClosureReminderProperties::new)
                .withUserConfiguration(ClosureReminderJob.class)
                .run(context -> assertThat(context).doesNotHaveBean(ClosureReminderJob.class));
    }

    @Test
    @DisplayName("job bean IS present when booking.closure-reminder.enabled=true — dry-run alone never gates bean construction")
    void should_haveJobBean_when_enabledPropertyTrue() {
        contextRunner
                .withPropertyValues("booking.closure-reminder.enabled=true")
                .withBean(ClosureReminderProperties.class, ClosureReminderProperties::new)
                .withUserConfiguration(ClosureReminderJob.class)
                .run(context -> assertThat(context).hasSingleBean(ClosureReminderJob.class));
    }

    // -------------------------------------------------------------------------
    // resolveWindow() — Clock-derived arithmetic, never Instant.now()
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("resolveWindow computes now/windowStart/windowEnd/dedupeCooldownBefore from the injected Clock")
    void should_computeWindow_when_defaultsUsed() {
        ClosureReminderProperties props = new ClosureReminderProperties();
        Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        ClosureReminderJob job = new ClosureReminderJob(mock(ClosureReminderClaimService.class), props, clock);
        OffsetDateTime now = OffsetDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC);

        ClosureReminderWindow window = job.resolveWindow();

        assertThat(window.now()).isEqualTo(now);
        assertThat(window.windowStart()).isEqualTo(now.minus(Duration.ofDays(14)));
        assertThat(window.windowEnd()).isEqualTo(now.minus(Duration.ofHours(2)));
        assertThat(window.dedupeCooldownBefore()).isEqualTo(now.minusDays(1));
        assertThat(window.maxPerProviderPerDay()).isEqualTo(3);
        assertThat(window.maxPerBooking()).isEqualTo(2);
        assertThat(window.maxTotalClaimsPerRun()).isEqualTo(1000);
    }

    // -------------------------------------------------------------------------
    // Hard clamps — configuration cannot exceed them
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("a configured 90-day lookback ceiling is clamped to 30 days")
    void should_clampLookbackMax_when_configuredCeilingExceeds30Days() {
        ClosureReminderProperties props = new ClosureReminderProperties();
        props.setLookbackMax(Duration.ofDays(90));

        assertThat(props.getLookbackMaxRaw()).isEqualTo(Duration.ofDays(90));
        assertThat(props.getLookbackMax()).isEqualTo(Duration.ofDays(30));
    }

    @Test
    @DisplayName("a configured 29-day lookback ceiling is left unclamped")
    void should_notClampLookbackMax_when_configuredCeilingIsBelow30Days() {
        ClosureReminderProperties props = new ClosureReminderProperties();
        props.setLookbackMax(Duration.ofDays(29));

        assertThat(props.getLookbackMax()).isEqualTo(Duration.ofDays(29));
    }

    @Test
    @DisplayName("a configured max-per-booking of 50 is clamped to 5")
    void should_clampMaxPerBooking_when_configuredValueExceeds5() {
        ClosureReminderProperties props = new ClosureReminderProperties();
        props.setMaxPerBooking(50);

        assertThat(props.getMaxPerBookingRaw()).isEqualTo(50);
        assertThat(props.getMaxPerBooking()).isEqualTo(5);
    }

    @Test
    @DisplayName("a configured max-per-booking of 4 is left unclamped")
    void should_notClampMaxPerBooking_when_configuredValueIsBelow5() {
        ClosureReminderProperties props = new ClosureReminderProperties();
        props.setMaxPerBooking(4);

        assertThat(props.getMaxPerBooking()).isEqualTo(4);
    }

    @Test
    @DisplayName("a configured max-total-claims-per-run of 10000 is clamped to 5000")
    void should_clampMaxTotalClaimsPerRun_when_configuredValueExceeds5000() {
        ClosureReminderProperties props = new ClosureReminderProperties();
        props.setMaxTotalClaimsPerRun(10_000);

        assertThat(props.getMaxTotalClaimsPerRunRaw()).isEqualTo(10_000);
        assertThat(props.getMaxTotalClaimsPerRun()).isEqualTo(5_000);
    }

    @Test
    @DisplayName("a configured max-total-claims-per-run of 4000 is left unclamped")
    void should_notClampMaxTotalClaimsPerRun_when_configuredValueIsBelow5000() {
        ClosureReminderProperties props = new ClosureReminderProperties();
        props.setMaxTotalClaimsPerRun(4_000);

        assertThat(props.getMaxTotalClaimsPerRun()).isEqualTo(4_000);
    }

    @Test
    @DisplayName("resolveWindow applies the clamped lookback ceiling, not the raw configured value")
    void should_useClampedLookbackMax_when_resolvingWindow() {
        ClosureReminderProperties props = new ClosureReminderProperties();
        props.setLookbackMax(Duration.ofDays(90));
        Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        ClosureReminderJob job = new ClosureReminderJob(mock(ClosureReminderClaimService.class), props, clock);
        OffsetDateTime now = OffsetDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC);

        ClosureReminderWindow window = job.resolveWindow();

        assertThat(window.windowStart())
                .as("must use the 30-day hard clamp, not the configured 90-day value")
                .isEqualTo(now.minus(Duration.ofDays(30)));
    }
}
