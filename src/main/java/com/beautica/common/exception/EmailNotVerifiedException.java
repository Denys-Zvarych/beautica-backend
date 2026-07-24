package com.beautica.common.exception;

public class EmailNotVerifiedException extends RuntimeException {

    private final String email;

    public EmailNotVerifiedException(String email) {
        super("Email not verified");
        this.email = email;
    }

    public String getEmail() {
        return email;
    }

    /**
     * Suppresses stack-trace capture for this flow-control exception.
     *
     * <p>EmailNotVerifiedException is thrown on every login attempt by an unverified
     * account and translated directly to an HTTP 403 by {@code GlobalExceptionHandler}.
     * Capturing a full stack trace allocates ~1–5 µs of CPU and several KB of heap per
     * call — wasted work because the trace is never logged or inspected. Overriding
     * {@link Throwable#fillInStackTrace()} to be a no-op eliminates that cost with zero
     * behavioural change (the handler only reads {@link #email}).
     */
    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
