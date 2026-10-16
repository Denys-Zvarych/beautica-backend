package com.beautica.notification.sms;

/** Booking-SMS {@link SmsService} for {@code beautica.sms.mode=capture}: delegates to {@link CapturingSmsSender}. */
public final class CapturingSmsService implements SmsService {

    private final CapturingSmsSender delegate;

    public CapturingSmsService(CapturingSmsSender delegate) {
        this.delegate = delegate;
    }

    @Override
    public void send(String phoneE164, String text) {
        delegate.send(phoneE164, text);
    }
}
