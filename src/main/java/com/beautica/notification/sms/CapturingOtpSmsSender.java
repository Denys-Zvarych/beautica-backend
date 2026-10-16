package com.beautica.notification.sms;

/**
 * Guest phone-OTP {@link OtpSmsSender} for {@code beautica.sms.mode=capture}: delegates to
 * {@link CapturingSmsSender}. Same contract as {@link TurbosmsOtpSmsSender} — throws
 * {@link SmsDeliveryException} on failure, so {@code PhoneOtpService}'s burn-on-failure flow is unchanged.
 */
public final class CapturingOtpSmsSender implements OtpSmsSender {

    private final CapturingSmsSender delegate;

    public CapturingOtpSmsSender(CapturingSmsSender delegate) {
        this.delegate = delegate;
    }

    @Override
    public void send(String phoneE164, String text) {
        delegate.send(phoneE164, text);
    }
}
