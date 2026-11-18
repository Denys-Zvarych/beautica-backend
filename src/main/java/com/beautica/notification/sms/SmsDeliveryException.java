package com.beautica.notification.sms;

/**
 * Thrown when an SMS could not be delivered to the provider — non-2xx HTTP
 * response, transport failure, or a provider-level error body (Phase 13.1).
 *
 * <p>Runtime exception so callers (OTP / notification flows) opt in to handling.
 * The message MUST NOT contain the recipient phone, message text (may include an
 * OTP), or the provider token.
 */
public class SmsDeliveryException extends RuntimeException {

    /** B520 audit-fix — a provider outage signal (transport failure, 5xx / 429), not a per-message refusal. */
    private final boolean transientFailure;

    public SmsDeliveryException(String message) {
        this(message, null, false);
    }

    public SmsDeliveryException(String message, Throwable cause) {
        this(message, cause, false);
    }

    /**
     * @param transientFailure {@code true} when the provider was unreachable or answered 5xx / 429 — the failure says
     *                         the provider is down, not that this message is bad; the outbox circuit breaker counts it
     */
    public SmsDeliveryException(String message, Throwable cause, boolean transientFailure) {
        super(message, cause);
        this.transientFailure = transientFailure;
    }

    /** Whether this failure signals a provider outage (see the constructor); {@code false} for a refusal. */
    public boolean isTransient() {
        return transientFailure;
    }
}
