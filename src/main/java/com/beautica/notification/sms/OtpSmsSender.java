package com.beautica.notification.sms;

/**
 * Outbound SMS for the <b>auth</b> channel — today, the guest phone-OTP
 * ({@code POST /api/v1/book/otp/send}).
 *
 * <h2>Why this is not {@link SmsService}</h2>
 * {@code app.booking.sms.enabled} is a <em>money</em> gate over booking copy: confirmations,
 * reminders, cancellations, declines. It selects which {@link SmsService} bean exists at all
 * ({@code SmsConfig}), which is what makes it impossible for a new booking sender to forget the
 * flag.
 *
 * <p>Applying that same gate to the OTP was a defect (security/perf/QA HIGH, 2026-08-18): with
 * {@code APP_BOOKING_SMS_ENABLED} unset, {@code POST /book/otp/send} returned 200, persisted the
 * hashed code, burnt the per-phone and per-IP budgets and delivered nothing — so the only guest
 * authentication flow the platform has could never be completed, silently. An OTP is not booking
 * spend; it is the credential that lets a guest reach the booking form in the first place, and the
 * phase's locked decision ("one flag for all outbound BOOKING SMS") never covered it.
 *
 * <p>Giving the OTP its own <b>type</b> — rather than a second, qualified {@link SmsService} bean —
 * keeps two properties that are worth more than the saved file:
 * <ul>
 *   <li>{@code SmsService} still resolves to <b>exactly one</b> bean in every profile, so a stray
 *       {@code @Service} restored on {@link TurbosmsService} still fails the context loudly
 *       ({@code NoUniqueBeanDefinitionException}) instead of being masked by a {@code @Primary};
 *       that invariant is asserted by {@code SmsFeatureGateTest} and {@code WalkInBookingSmsIT}.</li>
 *   <li>A booking sender cannot reach this interface by accident. Injecting {@code SmsService} is
 *       the only way to send booking copy, and it is always gated.</li>
 * </ul>
 *
 * <h2>Contract</h2>
 * Unlike {@link SmsService} while the gate is off, this sender is <b>always live</b> and
 * <b>throws</b> when delivery fails — including when {@code TURBOSMS_TOKEN} is blank, which
 * {@link TurbosmsService#send} reports as a clean {@link SmsDeliveryException}. That loudness is
 * deliberate and must not be softened: a guest who never receives a code must not be told the code
 * was sent. {@code PhoneOtpService} translates the failure into a 503 and lets the OTP row roll
 * back.
 */
@FunctionalInterface
public interface OtpSmsSender {

    /**
     * Delivers an auth SMS, or throws.
     *
     * @param phoneE164 recipient in E.164 ({@code +380...})
     * @param text      message body — never logged (Anti-Bug §I)
     * @throws SmsDeliveryException when the provider is unconfigured, rejects the request, or is
     *                              unreachable
     */
    void send(String phoneE164, String text);
}
