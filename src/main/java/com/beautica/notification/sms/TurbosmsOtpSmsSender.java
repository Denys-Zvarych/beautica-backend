package com.beautica.notification.sms;

/**
 * The {@link OtpSmsSender} the application always runs with: a thin adapter over its own
 * {@link TurbosmsService} instance.
 *
 * <h2>Why a wrapper and not {@code TurbosmsService implements OtpSmsSender}</h2>
 * Bean <b>type</b> is the whole mechanism here. If {@link TurbosmsService} implemented
 * {@link OtpSmsSender} directly, the always-on OTP bean would also match
 * {@code getBeanNamesForType(SmsService.class)} — turning the "exactly one gated
 * {@link SmsService}" invariant into "two", masking a restored {@code @Service} on
 * {@link TurbosmsService}, and making every bare {@code SmsService} injection point ambiguous at
 * boot. The wrapper's own class is not an {@link SmsService}, so the two channels stay disjoint in
 * the container while sharing one adapter implementation (DRY without coupling the types).
 *
 * <p>Its delegate is a <b>separate</b> {@link TurbosmsService} instance from the gated booking one,
 * so each channel owns its HTTP client and connection pool. That separation is a small bulkhead:
 * a Turbosms brown-out saturating booking sends cannot consume the auth channel's sockets.
 */
public final class TurbosmsOtpSmsSender implements OtpSmsSender {

    private final TurbosmsService delegate;

    public TurbosmsOtpSmsSender(TurbosmsService delegate) {
        this.delegate = delegate;
    }

    /** Delegates verbatim — including the loud throw on a blank token, which is the contract. */
    @Override
    public void send(String phoneE164, String text) {
        delegate.send(phoneE164, text);
    }
}
