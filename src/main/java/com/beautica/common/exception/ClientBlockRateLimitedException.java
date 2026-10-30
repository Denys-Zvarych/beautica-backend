package com.beautica.common.exception;

/**
 * Phase 397 audit-fix — the caller has used up their {@code RateLimitConfig#clientBlockWriteBuckets} budget for
 * {@code POST /api/v1/blocks} and {@code DELETE /api/v1/blocks/{id}} (per principal). Thrown by
 * {@code ClientBlockService}, translated to the standard 429 envelope with {@code Retry-After} by
 * {@code GlobalExceptionHandler}. Mirrors {@link ContentReportRateLimitedException}.
 */
public class ClientBlockRateLimitedException extends RuntimeException {

    private final long retryAfterSeconds;

    public ClientBlockRateLimitedException(long retryAfterSeconds) {
        super("Client block rate limit exceeded");
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
