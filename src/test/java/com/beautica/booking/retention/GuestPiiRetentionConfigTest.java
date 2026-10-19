package com.beautica.booking.retention;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.support.SimpleTriggerContext;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Phase 373 D1/D7 — binding of the SHIPPED {@code application.yml} / {@code application-test.yml}
 * to {@link GuestPiiRetentionProperties}, and the {@code @Scheduled} wiring of
 * {@link GuestPiiRetentionJob}. {@code GuestPiiRetentionJobTest} only constructs the record by
 * hand, so a renamed yml key (kebab-case drift), a dropped default, or a lost {@code zone = "UTC"}
 * would all pass it green.
 */
@DisplayName("GuestPiiRetention — yml binding + scheduled cron wiring")
class GuestPiiRetentionConfigTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(GuestPiiRetentionProperties.class)
    static class PropsOnly {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class Scheduling {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(PropsOnly.class);

    @Test
    @DisplayName("application.yml binds the production defaults: enabled, 03:27 cron, 12 months, 500 x 200")
    void should_bindProductionDefaults_when_noProfile() {
        runner.run(ctx -> assertThat(ctx.getBean(GuestPiiRetentionProperties.class))
                .isEqualTo(new GuestPiiRetentionProperties(true, "0 27 3 * * *", 12, 500, 200)));
    }

    @Test
    @DisplayName("prod profile keeps the same defaults (no override shortens retention)")
    void should_keepDefaults_when_prodProfile() {
        runner.withPropertyValues("spring.profiles.active=prod")
                .run(ctx -> assertThat(ctx.getBean(GuestPiiRetentionProperties.class))
                        .isEqualTo(new GuestPiiRetentionProperties(true, "0 27 3 * * *", 12, 500, 200)));
    }

    @Test
    @DisplayName("test profile disables the job but keeps retention/batch values")
    void should_disableJob_when_testProfile() {
        runner.withPropertyValues("spring.profiles.active=test")
                .run(ctx -> assertThat(ctx.getBean(GuestPiiRetentionProperties.class))
                        .isEqualTo(new GuestPiiRetentionProperties(false, "0 27 3 * * *", 12, 500, 200)));
    }

    @Test
    @DisplayName("an env/yml override of retention-months below 12 fails context startup (fail-fast, not silent)")
    void should_failStartup_when_retentionOverriddenBelowTwelve() {
        runner.withPropertyValues("app.guest-pii-retention.retention-months=11")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("retention-months must be >= 12");
                });
    }

    @Test
    @DisplayName("the job registers one cron task at 03:27 UTC — independent of the scheduler clock zone")
    void should_scheduleAt0327Utc_when_jobEnabled() {
        jobRunner().run(ctx -> {
            CronTask task = singleCronTask(ctx.getBean(ScheduledAnnotationBeanPostProcessor.class));
            assertThat(task.getExpression()).isEqualTo("0 27 3 * * *");
            // A zone-less CronTrigger falls back to the scheduler clock's zone. Drive it with a
            // Kyiv clock: if the job ever loses zone = "UTC", the next fire becomes 00:27Z.
            Instant next = task.getTrigger().nextExecution(new SimpleTriggerContext(
                    Clock.fixed(Instant.parse("2026-10-19T00:00:00Z"), ZoneId.of("Europe/Kyiv"))));
            assertThat(next).isEqualTo(Instant.parse("2026-10-19T03:27:00Z"));
        });
    }

    @Test
    @DisplayName("the cron comes from app.guest-pii-retention.cron, not a hard-coded literal")
    void should_useConfiguredCron_when_propertyOverridden() {
        jobRunner().withPropertyValues("app.guest-pii-retention.cron=0 5 4 * * *")
                .run(ctx -> assertThat(singleCronTask(ctx.getBean(ScheduledAnnotationBeanPostProcessor.class))
                        .getExpression()).isEqualTo("0 5 4 * * *"));
    }

    @Test
    @DisplayName("no scheduled task at all when the job is disabled")
    void should_registerNoTask_when_disabled() {
        jobRunner().withPropertyValues("app.guest-pii-retention.enabled=false")
                .run(ctx -> assertThat(ctx.getBean(ScheduledAnnotationBeanPostProcessor.class)
                        .getScheduledTasks()).isEmpty());
    }

    private ApplicationContextRunner jobRunner() {
        return runner
                .withUserConfiguration(Scheduling.class, GuestPiiRetentionJob.class)
                .withBean(GuestPiiAnonymisationService.class, () -> mock(GuestPiiAnonymisationService.class))
                .withBean(Clock.class, Clock::systemUTC);
    }

    private static CronTask singleCronTask(ScheduledAnnotationBeanPostProcessor processor) {
        List<ScheduledTask> tasks = List.copyOf(processor.getScheduledTasks());
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).getTask()).isInstanceOf(CronTask.class);
        return (CronTask) tasks.get(0).getTask();
    }
}
