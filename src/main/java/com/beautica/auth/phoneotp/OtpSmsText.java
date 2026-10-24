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
 * Код Beautica: 123456. Нікому не кажіть.
 *
 * &#64;beautica.app #123456
 * </pre>
 * Without a domain (local/test) only the first line is sent.
 *
 * <p>The host is validated once, in {@link #forDomain}, which pre-builds the domain suffix; the
 * per-send {@link #render} only checks the code and concatenates. {@code PhoneOtpService} builds one
 * instance at construction.
 *
 * <h2>One SMS segment</h2>
 * The text is Cyrillic, so it is sent as UCS-2: 70 characters per single segment. The first line is 39
 * characters, the domain line adds {@code 2 (blank line) + 1 + host + 2 + 6}, so the whole message is
 * {@code 50 + host.length()} characters. {@link OtpDomainRules#MAX_HOST_LENGTH} = 18 keeps every allowed
 * host inside one segment even if the gateway turns each {@code \n} into {@code \r\n}
 * ({@code beautica.app} → 62, 64 with CRLF). Boot-time validation of {@code beautica.sms.otp-domain}
 * uses the same constants ({@code DeploymentProperties.Sms}).
 *
 * <p>The text carries a live credential: never log it.
 */
public final class OtpSmsText {

    /** Single UCS-2 SMS segment. */
    static final int MAX_SINGLE_SEGMENT_CHARS = 70;

    private static final Pattern HOST = Pattern.compile(OtpDomainRules.HOST_REGEX);
    private static final Pattern CODE = Pattern.compile("\\d{6}");
    private static final String FIRST_LINE_PREFIX = "Код Beautica: ";
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
        if (code == null || !CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("OTP code must be exactly 6 digits");
        }
        String firstLine = FIRST_LINE_PREFIX + code + FIRST_LINE_SUFFIX;
        return domainSuffix.isEmpty() ? firstLine : firstLine + domainSuffix + code;
    }

    private static String requireValidHost(String host) {
        if (host.length() > OtpDomainRules.MAX_HOST_LENGTH || !HOST.matcher(host).matches()) {
            throw new IllegalArgumentException("OTP SMS domain must be a bare host of at most "
                    + OtpDomainRules.MAX_HOST_LENGTH + " characters");
        }
        return host;
    }
}
