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

    public SmsDeliveryException(String message) {
        super(message);
    }

    public SmsDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
