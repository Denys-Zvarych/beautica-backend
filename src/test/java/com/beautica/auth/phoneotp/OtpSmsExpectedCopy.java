package com.beautica.auth.phoneotp;

/**
 * Phase 440 — the ONE place tests spell out the guest OTP SMS text (pre-push rule 4, shared copy fixture).
 *
 * <p>The strings are written out literally on purpose, NOT built from {@link OtpSmsText}'s constants: a copy
 * change in production must fail these assertions, not silently move with them.
 */
public final class OtpSmsExpectedCopy {

    private OtpSmsExpectedCopy() {
    }

    /** {@code PLAIN} (and the no-domain text): the human line only, 43 UCS-2 characters. */
    public static String plain(String code) {
        return "Beautica: Ваш код " + code + ". Нікому не кажіть.";
    }

    /** {@code DOMAIN_BOUND} with a domain: human line, blank line, final {@code @host #code} line. */
    public static String domainBound(String code, String host) {
        return plain(code) + "\n\n@" + host + " #" + code;
    }
}
