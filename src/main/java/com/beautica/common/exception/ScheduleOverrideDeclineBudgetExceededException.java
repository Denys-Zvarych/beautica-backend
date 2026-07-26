package com.beautica.common.exception;

/**
 * Thrown when a {@code PUT /api/v1/masters/{masterId}/overrides/{date}} write would exceed the
 * caller's AGGREGATE per-window decline budget — {@code
 * RateLimitConfig#scheduleOverrideDeclineBudgetBuckets}, consumed directly by {@code
 * ScheduleOverrideConflictService#applyOverrideWithConflictHandling}.
 *
 * <p><b>Why this exists (2026-07-26 security audit finding 1, MEDIUM).</b> {@code
 * ScheduleOverrideConflictService.MAX_CONFLICTS_PER_WRITE} (100) bounds a single request, and
 * {@code RateLimitConfig#scheduleOverrideWriteCapacity} (50/60s) bounds request frequency — but the
 * two are independent, so nothing bounds their PRODUCT: an already-authorized
 * ({@code canManageMasterSchedule}) but compromised account could otherwise mass-decline on the
 * order of 5,000 {@code CONFIRMED} bookings per minute across every master it manages, with no
 * client notification (D6) to surface the damage. This exception is thrown when the aggregate,
 * actor-keyed token budget — charged {@code conflicts.size()} tokens per write, not 1 — is
 * exhausted, closing that gap without narrowing the legitimate single-write or single-minute
 * ceilings at all.
 *
 * <p>Mirrors {@link ResendThrottledException}: an application-level (not servlet-filter-level)
 * 429, because the token cost (the actual conflict count) is only known INSIDE the service after
 * the conflict scan runs — a {@code OncePerRequestFilter} sees only the request, never the
 * response-shaping conflict count, so this cannot be enforced at the filter layer the way the
 * flat one-token-per-request {@code scheduleOverrideWriteBuckets} bucket is.
 *
 * <p>{@link #retryAfterSeconds} is surfaced in the HTTP {@code Retry-After} response header by
 * {@code GlobalExceptionHandler}.
 */
public class ScheduleOverrideDeclineBudgetExceededException extends RuntimeException {

    private final long retryAfterSeconds;

    public ScheduleOverrideDeclineBudgetExceededException(long retryAfterSeconds) {
        super("Schedule override decline budget exceeded");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    /**
     * Suppresses stack-trace capture for this flow-control exception — mirrors
     * {@link ResendThrottledException#fillInStackTrace()}: thrown on every throttled request and
     * translated directly to an HTTP 429, so the trace is never logged or inspected.
     */
    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
