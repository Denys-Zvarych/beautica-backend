package com.beautica.booking.closure;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Phase 29.6 — the closure-reminder job, bounded from the first commit. Emits {@code
 * CLOSURE_REMINDER} outbox events for bookings awaiting provider closure (Phase 29.1's {@code
 * BookingClosureRule}), on a schedule, with every blast-radius control present from day one:
 * bounded lookback, per-provider daily cap, dedupe, a concurrency-safe atomic claim, and a kill
 * switch defaulting to {@code false}. This job NEVER writes {@code bookings.status} — see {@code
 * ClosureReminderArchitectureTest}, which scans this whole package.
 *
 * <p><b>Kill switch.</b> {@code @ConditionalOnProperty} means this bean is not even constructed
 * when {@code booking.closure-reminder.enabled} is absent or {@code false} — the default, and the
 * value in every committed {@code application*.yml}. There is no other way to make this job run.
 *
 * <p><b>Dry-run (Phase 29.7).</b> When enabled, {@code booking.closure-reminder.dry-run} (default
 * {@code true}) routes every run through {@link ClosureReminderClaimService#dryRun} instead of
 * {@link ClosureReminderClaimService#claimAndEnqueue} — same claim statement, rolled back, zero
 * outbox rows, zero {@code booking_closure_reminders} rows. Only a human flipping {@code
 * dry-run=false} on Railway (after reviewing the dry-run counts) lets this job write anything.
 *
 * <p><b>Kyiv boundary.</b> The {@code @Scheduled} cron's {@code zone = "Europe/Kyiv"} is the ONE
 * legitimate Kyiv reference in this entire track — a send-time-of-day policy is genuinely a
 * wall-clock concern. The candidate window computed in {@link #run()} stays an absolute-instant
 * comparison from {@link Clock#instant()}; {@code TimeZones.KYIV} must never leak into it.
 */
@Component
@ConditionalOnProperty(prefix = "booking.closure-reminder", name = "enabled", havingValue = "true")
@Slf4j
public class ClosureReminderJob {

    private final ClosureReminderClaimService claimService;
    private final ClosureReminderProperties properties;
    private final Clock clock;

    public ClosureReminderJob(
            ClosureReminderClaimService claimService,
            ClosureReminderProperties properties,
            Clock clock) {
        this.claimService = claimService;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "0 0 10 * * *", zone = "Europe/Kyiv")
    public void run() {
        ClosureReminderWindow window = resolveWindow();
        ClosureReminderRunResult result = properties.isDryRun()
                ? claimService.dryRun(window)
                : claimService.claimAndEnqueue(window);
        logResult(properties.isDryRun(), result);
    }

    /**
     * Resolves the run's absolute-instant window ONCE, from the injected {@link Clock} (Anti-Bug
     * §G — never {@code Instant.now()}/{@code OffsetDateTime.now()} directly). Package-private so
     * {@code ClosureReminderJobTest} can assert the arithmetic without invoking the scheduled
     * method itself.
     */
    ClosureReminderWindow resolveWindow() {
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        return new ClosureReminderWindow(
                now,
                now.minus(properties.getLookbackMax()),
                now.minus(properties.getLookbackMin()),
                now.minusDays(1),
                properties.getMaxPerProviderPerDay(),
                properties.getMaxPerBooking(),
                properties.getMaxTotalClaimsPerRun());
    }

    /**
     * ONE structured log line per run, aggregate numbers only — per the locked track-25 rule, no
     * booking id, no client name, no email address, and none of {@code clientComment} / {@code
     * clientCancellationNote} / {@code providerComment} ever appear here (this job never even
     * loads a full {@link com.beautica.booking.entity.Booking}, only ids and COUNT/MIN/MAX
     * scalars — there is nothing PII-shaped in scope to log by accident).
     */
    private void logResult(boolean dryRun, ClosureReminderRunResult result) {
        log.info("closure-reminder {}: candidates={} claimable={} providersAffected={} "
                        + "excludedByDedupe={} cappedByProviderLimit={} cappedByGlobalLimit={} "
                        + "suppressedByDedupe={} oldestCandidateEndsAt={} newestCandidateEndsAt={}",
                dryRun ? "dry-run" : "run",
                result.candidateCount(), result.claimable(), result.providersAffected(),
                result.excludedByDedupe(), result.cappedByProviderLimit(), result.cappedByGlobalLimit(),
                result.suppressedByDedupe(), result.oldestCandidateEndsAt(), result.newestCandidateEndsAt());
    }
}
