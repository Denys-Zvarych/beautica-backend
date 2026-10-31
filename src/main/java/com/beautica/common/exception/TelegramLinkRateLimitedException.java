package com.beautica.common.exception;

/**
 * Phase 407 D5 — one cancel token has used up its {@code RateLimitConfig#telegramLinkTokenBuckets} budget for
 * {@code POST /api/v1/book/telegram/link-token}. Thrown by {@code GuestTelegramLinkService}, translated to the standard
 * 429 envelope with {@code Retry-After} by {@code GlobalExceptionHandler}. Mirrors {@link ClientBlockRateLimitedException}.
 */
public class TelegramLinkRateLimitedException extends RuntimeException {

    private final long retryAfterSeconds;

    public TelegramLinkRateLimitedException(long retryAfterSeconds) {
        super("Telegram link-token rate limit exceeded");
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
