package com.beautica.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Server-side secret ("pepper") for keyed HMAC-SHA256 hashing of the 6-digit
 * email-verification OTP.
 *
 * <p>A bare {@code SHA-256(otp)} over a 1,000,000-value space is offline-recoverable
 * in well under a second from any hash disclosure (DB dump, log leak, backup theft).
 * Mixing in a server-only secret via HMAC makes the stored digest useless to an
 * attacker who does not also hold this key.
 *
 * <p>Fail-fast: the application refuses to start if the pepper is missing or shorter
 * than 32 characters, mirroring {@link JwtConfig}'s validation of {@code JWT_SECRET}.
 * Sourced from the {@code APP_OTP_PEPPER} environment variable in prod; a fixed dev
 * value lives in {@code application-local.yml} / {@code application-test.yml}.
 */
@ConfigurationProperties(prefix = "app.security")
public record OtpPepperConfig(
        String otpPepper
) {

    private static final int MIN_PEPPER_LENGTH = 32;

    public OtpPepperConfig {
        if (otpPepper == null || otpPepper.length() < MIN_PEPPER_LENGTH) {
            throw new IllegalStateException(
                    "app.security.otp-pepper must be at least " + MIN_PEPPER_LENGTH
                            + " characters — refusing to start with a weak OTP pepper");
        }
    }
}
