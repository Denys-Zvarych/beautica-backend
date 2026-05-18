package com.beautica.common.exception;

/**
 * Thrown when a resend-verification request arrives within the per-account cooldown window.
 *
 * <p>This is an application-level throttle that protects a single account from rapid
 * re-sends regardless of IP. It is complementary to the IP-level Bucket4j filter on
 * {@code /auth/*} endpoints (phase 1.8) which guards against volumetric abuse.
 *
 * <p>{@link #retryAfterSeconds} is the number of seconds the caller must wait before
 * the next resend is permitted. It is surfaced in the HTTP {@code Retry-After} response
 * header by {@code GlobalExceptionHandler}.
 */
public class ResendThrottledException extends RuntimeException {

    private final long retryAfterSeconds;

    public ResendThrottledException(long retryAfterSeconds) {
        super("Resend throttled");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    /**
     * Suppresses stack-trace capture for this flow-control exception.
     *
     * <p>ResendThrottledException is thrown on every throttled request and translated
     * directly to an HTTP 429 by {@code GlobalExceptionHandler}. Capturing a full
     * stack trace allocates ~1–5 µs of CPU and several KB of heap per call — wasted
     * work because the trace is never logged or inspected. Overriding
     * {@link Throwable#fillInStackTrace()} to be a no-op eliminates that cost with
     * zero behavioural change (the handler only reads {@link #retryAfterSeconds}).
     */
    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
