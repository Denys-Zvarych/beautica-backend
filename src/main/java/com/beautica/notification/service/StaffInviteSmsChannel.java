package com.beautica.notification.service;

/**
 * B520 (NTF-59) — the transport port of the staff invite SMS, the one non-OTP SMS NTF-D14 allows and, like an OTP, NOT
 * behind {@code app.booking.sms.enabled}. Implemented in {@code com.beautica.auth} ({@code StaffInviteSmsTransport}),
 * the only package allowed to reach the ungated {@code OtpSmsSender} ({@code SmsFeatureGateTest}); the outbox drain
 * depends on this port only, so no notification class ever holds the ungated sender.
 */
@FunctionalInterface
public interface StaffInviteSmsChannel {

    /**
     * Sends the rendered invite SMS, or throws so the outbox retries the row.
     *
     * @param phoneE164 the invited {@code +380…} number
     * @param text      the rendered body — never logged (carries a live invite token)
     */
    void send(String phoneE164, String text);
}
