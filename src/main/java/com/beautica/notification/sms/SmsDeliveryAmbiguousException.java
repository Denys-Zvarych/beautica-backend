package com.beautica.notification.sms;

/**
 * Phase 400 (audit cycle 1) — an {@link SmsDeliveryException} whose outcome is UNKNOWN: the request was on the wire
 * (read timeout, connection lost after the request was written) and the provider may already have accepted, billed
 * and delivered the message.
 *
 * <p>A definite refusal (HTTP error status, connection refused / connect timeout / unknown host — the request never
 * reached the provider) stays a plain {@link SmsDeliveryException}. Callers that retry a failed send must NOT retry
 * this subtype, or a slow provider turns into a duplicate SMS. Every existing caller catches the supertype and is
 * unaffected.
 *
 * <p>Same message discipline as the supertype: never the recipient, the text or the provider token.
 */
public class SmsDeliveryAmbiguousException extends SmsDeliveryException {

    public SmsDeliveryAmbiguousException(String message, Throwable cause) {
        super(message, cause);
    }
}
