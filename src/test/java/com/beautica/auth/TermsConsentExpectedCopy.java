package com.beautica.auth;

/**
 * Phase 442 — the ONE place tests spell out the terms-consent validation messages the client receives in the
 * {@code errors} map (pre-push rule: shared copy fixture, no re-typed literals).
 *
 * <p>Written out literally on purpose, NOT read from {@code TermsConsentCompleteValidator}'s constants: a copy
 * change in production must fail these assertions, not silently move with them.
 */
public final class TermsConsentExpectedCopy {

    /** {@code errors.termsAccepted} — false or missing acceptance ({@code @AssertTrue} and the class rule agree). */
    public static final String TERMS_REQUIRED = "Terms must be accepted";

    /** {@code errors.termsVersion} — acceptance sent without the terms version. */
    public static final String TERMS_VERSION_REQUIRED = "Terms version is required";

    /** {@code errors.privacyPolicyVersion} — acceptance sent without the privacy-policy version. */
    public static final String PRIVACY_VERSION_REQUIRED = "Privacy policy version is required";

    private TermsConsentExpectedCopy() {
    }
}
