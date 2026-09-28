package com.beautica.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableScheduling
public class SchedulingConfig {

    private static final Logger log = LoggerFactory.getLogger(SchedulingConfig.class);

    /**
     * Shared pool size for every {@code @Scheduled} task in this codebase (9 as of phase 335:
     * {@code NotificationOutboxDrainWorker}'s 5-second drain + reclaim pair, five daily
     * {@code *CleanupJob} sweeps including the phase-335 {@code InAppNotificationCleanupJob}
     * retention sweep, {@code BookingReminderJob}, {@code ClosureReminderJob}). Note: {@code
     * BookingReminderJob} runs {@code @Scheduled(cron = "0 0 * * * *")} — no {@code zone}, so the
     * default (UTC on Railway) — every hour on the hour, which means its {@code 03:00} tick also
     * lands inside the {@code 03:00-03:57} cleanup cluster described on {@code
     * InAppNotificationCleanupJob}'s own javadoc (alongside the outbox purge/verification/
     * password-reset/refresh-token jobs); that job's own duration is bounded by its 23-25h lookahead
     * window rather than an unbounded backlog, so it has not needed the same deliberate-slot
     * treatment those cleanup jobs got, but it is one more occupant of that window worth knowing
     * about if this pool is ever resized down. Raised from 2 to 4
     * (audit-fix cycle 1, finding 1) — at 2, the 5-second outbox drain could be starved for its
     * entire cadence by the retention sweep's own {@code maxBatchesPerRun}-bounded loop (up to 50
     * sequential {@code REQUIRES_NEW} batches) holding the other thread. Not yet
     * {@code @ConfigurationProperties}-driven: no other knob on this bean is either, and a plain
     * constant is simpler than adding a one-field config class for a single ops-tunable number that
     * has never needed tuning in this codebase.
     */
    private static final int SCHEDULER_POOL_SIZE = 4;

    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(SCHEDULER_POOL_SIZE);
        scheduler.setThreadNamePrefix("sched-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(20);
        scheduler.setErrorHandler(t ->
                log.error("Scheduled task error [{}]: {}", t.getClass().getSimpleName(), t.getMessage()));
        return scheduler;
    }
}
