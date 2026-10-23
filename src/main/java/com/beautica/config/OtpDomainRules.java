package com.beautica.config;

/**
 * Phase 389 — the shape rules for {@code beautica.sms.otp-domain}, the bare host bound to the guest OTP
 * SMS's last line {@code @host #code}. Config-level so {@link DeploymentProperties} can use them in its
 * boot-time constraints without depending on a feature package; {@code auth.phoneotp.OtpSmsText} reads
 * the same constants when it builds the text.
 *
 * <h2>Why 18</h2>
 * The rendered SMS is {@code 50 + host} UCS-2 characters (see {@code OtpSmsText}); one segment is 70. 18
 * leaves 2 characters of headroom so the text still fits one segment if the gateway rewrites the two
 * {@code \n} into {@code \r\n} ({@code 50 + 18 + 2 = 70}). {@code beautica.app} (12) and
 * {@code stg.beautica.app} (16) both fit.
 */
public final class OtpDomainRules {

    /** Bare lower-case DNS host: dot-separated labels, no leading/trailing hyphen, at least one dot. */
    public static final String HOST_REGEX =
            "[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+";

    /** Longest host that keeps the rendered text within one UCS-2 segment, even with CRLF line breaks. */
    public static final int MAX_HOST_LENGTH = 18;

    private OtpDomainRules() {
    }
}
