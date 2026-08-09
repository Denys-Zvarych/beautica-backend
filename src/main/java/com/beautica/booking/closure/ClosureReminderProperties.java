package com.beautica.booking.closure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Typed config for the Phase 29.6/29.7 closure-reminder job. Binding prefix {@code
 * booking.closure-reminder}.
 *
 * <p><b>{@link #enabled} defaults to {@code false} and is absent from every committed
 * {@code application*.yml}.</b> With the property unset (or explicitly {@code false}), {@link
 * ClosureReminderJob}'s {@code @ConditionalOnProperty} means the bean is never constructed — the
 * job cannot run in any environment until a human sets {@code
 * booking.closure-reminder.enabled=true} as a Railway environment variable (phase 29.7's rollout
 * procedure). This is deliberately a DIFFERENT property from {@code FIREBASE_ENABLED}: that flag
 * only mutes FCM/APNs push, but this job's reminder is an EMAIL, delivered through Spring Mail /
 * Brevo SMTP, which stays live in production regardless of the Firebase flag.
 *
 * <p><b>{@link #dryRun} defaults to {@code true}.</b> Phase 29.7 — even once {@link #enabled} is
 * flipped on, the job computes the claim set and writes nothing (see {@code
 * ClosureReminderClaimService#dryRun}) until a human reviews at least three consecutive days of
 * dry-run counts and explicitly sets {@code dry-run=false}.
 *
 * <p>{@link #getLookbackMax()} and {@link #getMaxPerBooking()} apply a HARD clamp that
 * configuration cannot exceed, on top of whatever value was bound — the raw {@code
 * lookbackMax}/{@code maxPerBooking} fields hold the configured value verbatim (so a
 * misconfiguration is visible, not silently rewritten), but every caller MUST read the clamped
 * value through the getter, never the field.
 */
@ConfigurationProperties(prefix = "booking.closure-reminder")
public class ClosureReminderProperties {

    /** No lookback ceiling may exceed this, regardless of what {@link #lookbackMax} is set to. */
    public static final Duration LOOKBACK_MAX_HARD_CLAMP = Duration.ofDays(30);

    /** No per-booking cap may exceed this, regardless of what {@link #maxPerBooking} is set to. */
    public static final int MAX_PER_BOOKING_HARD_CLAMP = 5;

    /** No global per-run claim cap may exceed this, regardless of what {@link
     * #maxTotalClaimsPerRun} is set to. */
    public static final int MAX_TOTAL_CLAIMS_PER_RUN_HARD_CLAMP = 5_000;

    private boolean enabled = false;

    private boolean dryRun = true;

    /**
     * The lookback CEILING — candidates whose {@code endsAt} is older than {@code now - this} are
     * never claimed. Defaults to 14 days; without this bound the first run would remind about
     * every unclosed booking in the platform's history in one burst. Hard-clamped to {@link
     * #LOOKBACK_MAX_HARD_CLAMP} by {@link #getLookbackMax()} — configuration cannot push it past
     * 30 days.
     */
    private Duration lookbackMax = Duration.ofDays(14);

    /**
     * The lookback FLOOR — candidates whose {@code endsAt} is more recent than {@code now - this}
     * are never claimed. Defaults to 2 hours, so a provider still mid-cleanup on a visit that
     * ended minutes ago is not nudged.
     */
    private Duration lookbackMin = Duration.ofHours(2);

    /**
     * Per-provider daily cap — at most this many bookings are claimed for one master in one run.
     * Enforced inside the native claim statement's window function, not by a post-filter (a
     * post-filter would still have claimed the rows it discards). Default 3.
     */
    private int maxPerProviderPerDay = 3;

    /**
     * Dedupe ceiling — at most this many reminders are ever sent for one booking. Default 2,
     * hard-clamped to {@link #MAX_PER_BOOKING_HARD_CLAMP} by {@link #getMaxPerBooking()}.
     */
    private int maxPerBooking = 2;

    /**
     * Global cap on the TOTAL number of bookings claimed in ONE run, regardless of how many
     * distinct masters are eligible — independent of {@link #maxPerProviderPerDay}, which only
     * bounds claims PER MASTER. Without this, a run's total claim volume scales with {@code
     * distinct_masters × maxPerProviderPerDay}: at 5,000 masters × the default cap of 3, that is
     * 15,000 {@code CLOSURE_REMINDER} rows landing, in one burst, in the SAME {@code
     * notification_outbox} the drain worker services strictly FIFO ({@code
     * NotificationOutboxRepository#claimPendingBatch}, {@code BATCH_SIZE=50} every 5s ≈ 600
     * rows/min) — every row created AFTER that burst (a fresh {@code BOOKING_CONFIRMED}, a {@code
     * REVIEW_REQUESTED}) would queue behind it, delaying time-sensitive notifications by tens of
     * minutes.
     *
     * <p>Enforced as an outer {@code LIMIT} in {@link ClosureReminderRepository#claimDueReminders},
     * applied AFTER the per-provider cap and ordered oldest-{@code endsAt}-first, so a run that
     * hits the cap always claims the platform's OLDEST unclosed bookings first and simply "catches
     * up" further on the next scheduled run — never an arbitrary, unfair subset.
     *
     * <p>Default 1000 (≈100 seconds to drain at the worker's ~600 rows/min steady-state rate — a
     * bounded, acceptable delay even for a large burst). Hard-clamped to {@link
     * #MAX_TOTAL_CLAIMS_PER_RUN_HARD_CLAMP} (5,000, ≈8.3 minutes to drain) by {@link
     * #getMaxTotalClaimsPerRun()} — configuration cannot push it higher. A dedicated priority lane
     * in the drain worker (so closure reminders never queue ahead of time-sensitive notification
     * types at all) is a larger structural change, deliberately out of scope here — see the
     * phase-224 doc's Status block.
     */
    private int maxTotalClaimsPerRun = 1000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

    /** Raw configured value, unclamped — see {@link #getLookbackMax()} for the value to actually use. */
    public Duration getLookbackMaxRaw() {
        return lookbackMax;
    }

    public void setLookbackMax(Duration lookbackMax) {
        this.lookbackMax = lookbackMax;
    }

    /** The lookback ceiling to use — clamps {@link #lookbackMax} to {@link #LOOKBACK_MAX_HARD_CLAMP}. */
    public Duration getLookbackMax() {
        return lookbackMax.compareTo(LOOKBACK_MAX_HARD_CLAMP) > 0 ? LOOKBACK_MAX_HARD_CLAMP : lookbackMax;
    }

    public Duration getLookbackMin() {
        return lookbackMin;
    }

    public void setLookbackMin(Duration lookbackMin) {
        this.lookbackMin = lookbackMin;
    }

    public int getMaxPerProviderPerDay() {
        return maxPerProviderPerDay;
    }

    public void setMaxPerProviderPerDay(int maxPerProviderPerDay) {
        this.maxPerProviderPerDay = maxPerProviderPerDay;
    }

    /** Raw configured value, unclamped — see {@link #getMaxPerBooking()} for the value to actually use. */
    public int getMaxPerBookingRaw() {
        return maxPerBooking;
    }

    public void setMaxPerBooking(int maxPerBooking) {
        this.maxPerBooking = maxPerBooking;
    }

    /** The per-booking dedupe ceiling to use — clamps {@link #maxPerBooking} to {@link #MAX_PER_BOOKING_HARD_CLAMP}. */
    public int getMaxPerBooking() {
        return Math.min(maxPerBooking, MAX_PER_BOOKING_HARD_CLAMP);
    }

    /** Raw configured value, unclamped — see {@link #getMaxTotalClaimsPerRun()} for the value to actually use. */
    public int getMaxTotalClaimsPerRunRaw() {
        return maxTotalClaimsPerRun;
    }

    public void setMaxTotalClaimsPerRun(int maxTotalClaimsPerRun) {
        this.maxTotalClaimsPerRun = maxTotalClaimsPerRun;
    }

    /** The global per-run claim cap to use — clamps {@link #maxTotalClaimsPerRun} to {@link #MAX_TOTAL_CLAIMS_PER_RUN_HARD_CLAMP}. */
    public int getMaxTotalClaimsPerRun() {
        return Math.min(maxTotalClaimsPerRun, MAX_TOTAL_CLAIMS_PER_RUN_HARD_CLAMP);
    }
}
