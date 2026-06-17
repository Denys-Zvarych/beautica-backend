package com.beautica.notification.sms;

/**
 * Provider-agnostic SMS sending port (Phase 13.1). The current implementation is
 * {@link TurbosmsService}; the interface lets the OTP and notification layers depend
 * on the abstraction rather than the concrete provider (DIP).
 */
public interface SmsService {

    /**
     * Sends an SMS to a single recipient.
     *
     * @param phoneE164 recipient phone in E.164 format ({@code +380XXXXXXXXX})
     * @param text      message body (may contain an OTP — never logged)
     * @throws SmsDeliveryException when the provider rejects the request or returns
     *                              a non-2xx response
     */
    void send(String phoneE164, String text);
}
