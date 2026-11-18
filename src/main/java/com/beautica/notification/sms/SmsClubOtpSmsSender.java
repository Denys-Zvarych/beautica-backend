package com.beautica.notification.sms;

/**
 * The {@link OtpSmsSender} for {@code beautica.sms.mode=smsclub} (Phase 439): a thin adapter over its own
 * {@link SmsClubService} instance, built with the OTP {@code lifetime}.
 *
 * <p>A wrapper rather than {@code SmsClubService implements OtpSmsSender} for the reason given on
 * {@link TurbosmsOtpSmsSender}: the always-on OTP bean must stay outside the {@link SmsService} type, or the
 * exactly-one-{@link SmsService} invariant of {@code SmsConfig} collapses. Its delegate is a separate instance from
 * the gated booking one, so each channel owns its HTTP client (a small bulkhead).
 */
public final class SmsClubOtpSmsSender implements OtpSmsSender {

    private final SmsClubService delegate;
    /** B520 audit-fix — its own instance: invite lifetime (≥ 24 h) and the shorter invite timeouts. */
    private final SmsClubService inviteDelegate;

    /** OTP and staff invite through the same instance (tests; the bean uses the two-delegate form). */
    public SmsClubOtpSmsSender(SmsClubService delegate) {
        this(delegate, delegate);
    }

    public SmsClubOtpSmsSender(SmsClubService delegate, SmsClubService inviteDelegate) {
        this.delegate = delegate;
        this.inviteDelegate = inviteDelegate;
    }

    /** Delegates verbatim — including the loud throw on a blank token, which is the contract. */
    @Override
    public void send(String phoneE164, String text) {
        delegate.send(phoneE164, text);
    }

    @Override
    public void sendStaffInvite(String phoneE164, String text) {
        inviteDelegate.send(phoneE164, text);
    }
}
