package com.beautica.auth.phoneotp;

import com.beautica.config.OtpDomainRules;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Builds the guest phone-OTP SMS text (Phase 389). Immutable, no user data in the output.
 *
 * <p>With a domain the text is two paragraphs, the last line being the origin-bound code line that
 * WebOTP (Chrome Android) and Apple domain-bound codes (iOS Safari) require: it must be the FINAL line,
 * formatted exactly {@code @host #code}, host bare (no scheme, port or path):
 * <pre>
 * Beautica: Ваш код 123456. Нікому не кажіть.
 *
 * &#64;beautica.app #123456
 * </pre>
 * Without a domain (local/test) only the first line is sent.
 *
 * <p>Phase 440 — {@link OtpSmsFormat#PLAIN} sends only the first line even with a domain (in-app browsers that
 * do not offer domain-bound codes); {@link #render(String)} is {@link OtpSmsFormat#DOMAIN_BOUND}.
 *
 * <p>The host is validated once, in {@link #forDomain}, which pre-builds the domain suffix; the
 * per-send {@link #render} only checks the code and concatenates. {@code PhoneOtpService} builds one
 * instance at construction.
 *
 * <h2>One SMS segment</h2>
 * The text is Cyrillic, so it is sent as UCS-2: 70 characters per single segment. The first line is 43
 * characters (phase 435: «Beautica:» title first, user decision 2026-11-04 — was 39), the domain line adds
 * {@code 2 (blank line) + 1 + host + 2 + 6}, so the whole message is {@code 54 + host.length()} characters.
 * {@link OtpDomainRules#MAX_HOST_LENGTH} = 14 keeps every allowed host inside one segment even if the gateway
 * turns each {@code \n} into {@code \r\n} ({@code beautica.app} → 66, 68 with CRLF). Boot-time validation of {@code beautica.sms.otp-domain}
 * uses the same constants ({@code DeploymentProperties.Sms}).
 *
 * <p>The text carries a live credential: never log it.
 */
public final class OtpSmsText {

    /** Single UCS-2 SMS segment. */
    static final int MAX_SINGLE_SEGMENT_CHARS = 70;

    private static final Pattern HOST = Pattern.compile(OtpDomainRules.HOST_REGEX);
    private static final Pattern CODE = Pattern.compile("\\d{6}");
    private static final String FIRST_LINE_PREFIX = "Beautica: Ваш код ";
    private static final String FIRST_LINE_SUFFIX = ". Нікому не кажіть.";

    /** {@code "\n\n@host #"}, or empty when there is no domain line. */
    private final String domainSuffix;

    private OtpSmsText(String domainSuffix) {
        this.domainSuffix = domainSuffix;
    }

    /**
     * @param domain the bare website host the code is bound to; empty = no origin-bound line
     * @throws IllegalArgumentException on a malformed or over-long host (config error, fails at boot)
     */
    public static OtpSmsText forDomain(Optional<String> domain) {
        return new OtpSmsText(domain.map(host -> "\n\n@" + requireValidHost(host) + " #").orElse(""));
    }

    /**
     * @param code the 6-digit one-time code
     * @return the SMS text, at most {@value #MAX_SINGLE_SEGMENT_CHARS} characters
     * @throws IllegalArgumentException on a malformed code (programming error)
     */
    public String render(String code) {
        return render(code, OtpSmsFormat.DOMAIN_BOUND);
    }

    /**
     * Phase 440.
     *
     * @param code   the 6-digit one-time code
     * @param format {@link OtpSmsFormat#PLAIN} = first line only; {@link OtpSmsFormat#DOMAIN_BOUND} = today's text
     * @return the SMS text, at most {@value #MAX_SINGLE_SEGMENT_CHARS} characters
     * @throws IllegalArgumentException on a malformed code (programming error)
     */
    public String render(String code, OtpSmsFormat format) {
        if (code == null || !CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("OTP code must be exactly 6 digits");
        }
        String firstLine = FIRST_LINE_PREFIX + code + FIRST_LINE_SUFFIX;
        boolean domainLine = format != OtpSmsFormat.PLAIN && !domainSuffix.isEmpty();
        return domainLine ? firstLine + domainSuffix + code : firstLine;
    }

    private static String requireValidHost(String host) {
        if (host.length() > OtpDomainRules.MAX_HOST_LENGTH || !HOST.matcher(host).matches()) {
            throw new IllegalArgumentException("OTP SMS domain must be a bare host of at most "
                    + OtpDomainRules.MAX_HOST_LENGTH + " characters");
        }
        return host;
    }
}
