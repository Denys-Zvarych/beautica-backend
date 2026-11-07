package com.beautica.auth.phoneotp;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * Phase 440 — which guest OTP SMS text to send, chosen by the caller ({@code smsFormat} on
 * {@code POST /api/v1/book/otp/send}). The website decides (phase 029); the server never reads the User-Agent.
 *
 * <p>Parsing is lenient on purpose (additions-only API rule): a missing, blank or unknown value falls back to
 * {@link #DOMAIN_BOUND}, never a 400, so an old or future website build can never break a send.
 */
public enum OtpSmsFormat {

    /** Today's text: human line plus the origin-bound {@code @host #code} last line (when a domain is set). */
    DOMAIN_BOUND("domain_bound"),

    /** Human line only, for in-app browsers that do not offer domain-bound codes. */
    PLAIN("plain");

    private final String metricTag;

    OtpSmsFormat(String metricTag) {
        this.metricTag = metricTag;
    }

    /** Lower-case tag value for the {@code beautica.otp_sms.sent{format}} counter. */
    public String metricTag() {
        return metricTag;
    }

    /**
     * @param raw the request value, may be {@code null}
     * @return the matching format (trimmed, case-insensitive), else {@link #DOMAIN_BOUND}
     */
    public static OtpSmsFormat fromRequest(String raw) {
        return match(raw).orElse(DOMAIN_BOUND);
    }

    /** @return {@code true} when {@code raw} is non-blank and names no format (feeds the fallback counter). */
    public static boolean isUnrecognised(String raw) {
        return raw != null && !raw.isBlank() && match(raw).isEmpty();
    }

    private static Optional<OtpSmsFormat> match(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalised = raw.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values()).filter(format -> format.name().equals(normalised)).findFirst();
    }
}
