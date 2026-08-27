package com.beautica.notification.sms;

/**
 * The single masking rule every {@link SmsService} implementation logs recipients through
 * (Phase 22.7).
 *
 * <p><b>Why this exists as its own type.</b> {@link TurbosmsService} owned this helper privately
 * until {@link NoOpSmsService} needed the identical rule. Copying it would have created two
 * masking rules that drift independently — and a drifted mask is not a cosmetic bug: it is a PII
 * leak (Anti-Bug §I-3, "never log an unmasked phone number"). One implementation, one behaviour,
 * asserted once.
 *
 * <p>Package-private on purpose: masking is an implementation detail of the SMS adapter layer, and
 * nothing outside {@code com.beautica.notification.sms} logs a raw recipient.
 */
final class PhoneMask {

    /** Number of trailing digits left visible — enough to correlate a support ticket, not to dial. */
    private static final int VISIBLE_TAIL_DIGITS = 4;

    /** Rendered when the recipient is null/blank, so a logging call can never NPE its own log line. */
    private static final String UNKNOWN = "+380***????";

    private PhoneMask() {
    }

    /**
     * Masks a phone to {@code +380***XXXX} — a fixed {@code +380} prefix plus the last
     * {@value #VISIBLE_TAIL_DIGITS} characters, with everything between hidden.
     *
     * <p>Defensive against null, blank and shorter-than-four input: logging must never be the thing
     * that throws inside a catch block.
     *
     * @param phone recipient in E.164 ({@code +380XXXXXXXXX}); may be null or blank
     * @return a log-safe rendering, never null
     */
    static String mask(String phone) {
        if (phone == null || phone.isBlank()) {
            return UNKNOWN;
        }
        String tail = phone.length() <= VISIBLE_TAIL_DIGITS
                ? phone
                : phone.substring(phone.length() - VISIBLE_TAIL_DIGITS);
        return "+380***" + tail;
    }
}
