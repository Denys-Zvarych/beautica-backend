package com.beautica.common.exception;

/**
 * Phase 395 D6 — the reporter has used up their {@code RateLimitConfig#contentReportBuckets} budget
 * (20 new reports per 24 hours per user). Thrown by {@code ContentReportService}, not a servlet filter, so an
 * idempotent repeat of an existing report (D4) is answered without spending a token; translated to HTTP 429
 * with {@code Retry-After} by {@code GlobalExceptionHandler}. Mirrors {@link ResendThrottledException}.
 */
public class ContentReportRateLimitedException extends RuntimeException {

    private final long retryAfterSeconds;

    public ContentReportRateLimitedException(long retryAfterSeconds) {
        super("Content report rate limit exceeded");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    /** Flow-control exception translated straight to a 429 — the stack trace is never inspected. */
    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
