package com.beautica.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Phase 397 D3 — server-side secret ("pepper") for the keyed HMAC-SHA256 phone hash
 * ({@link com.beautica.common.security.PhoneHasher}).
 *
 * <p>Deliberately NOT {@code APP_OTP_PEPPER}: the OTP pepper protects 5-minute codes and must stay freely
 * rotatable, whereas a phone-hash pepper can never rotate without orphaning every stored block (and, from
 * phase 399, every SMS opt-out). A bare {@code SHA-256(phone)} is rejected — the Ukrainian mobile space is
 * small enough to brute-force offline from a DB dump.
 *
 * <p>Fail-fast: the application refuses to start if the pepper is missing or shorter than 32 characters,
 * mirroring {@link OtpPepperConfig}. Sourced from the {@code PHONE_HASH_PEPPER} environment variable in prod;
 * fixed dev values live in {@code application-local.yml} / {@code application-test.yml}.
 */
@ConfigurationProperties(prefix = "app.security")
public record PhoneHashPepperConfig(
        String phoneHashPepper
) {

    private static final int MIN_PEPPER_LENGTH = 32;

    public PhoneHashPepperConfig {
        if (phoneHashPepper == null || phoneHashPepper.length() < MIN_PEPPER_LENGTH) {
            throw new IllegalStateException(
                    "app.security.phone-hash-pepper must be at least " + MIN_PEPPER_LENGTH
                            + " characters — refusing to start with a weak phone-hash pepper");
        }
    }

    /** Redacted: the generated record {@code toString} would print the secret. */
    @Override
    public String toString() {
        return "PhoneHashPepperConfig[phoneHashPepper=<redacted>]";
    }
}
