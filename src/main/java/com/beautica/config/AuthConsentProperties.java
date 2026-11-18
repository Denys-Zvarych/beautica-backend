package com.beautica.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Phase 442 — terms + privacy consent at account creation.
 *
 * <p>Binding prefix {@code app.auth}: {@code require-terms-consent} (env {@code APP_AUTH_REQUIRE_TERMS_CONSENT}),
 * default {@code false}. While {@code false}, a register / invite-accept request carrying NO consent fields is
 * accepted (legacy Play builds) and stores nothing. Partial or false consent is rejected in every mode. Flip to
 * {@code true} only after the consent build is live on Play AND {@code APP_ANDROID_MIN_VERSION_CODE} forces it.
 * Read by {@code TermsConsentCompleteValidator}.
 */
@ConfigurationProperties(prefix = "app.auth")
public class AuthConsentProperties {

    private boolean requireTermsConsent = false;

    /**
     * B514 (AUTH-02, AUTH-43) — {@code app.auth.require-phone-registration} (env
     * {@code APP_AUTH_REQUIRE_PHONE_REGISTRATION}), default {@code false}. While {@code false}, register /
     * register-independent-master accept the legacy email + password shape (email-code path). Invite accept is not
     * gated by it since B520: a phone invite always needs a {@code registrationTicket} for the invited phone. Flip to {@code true} only after M515 is the Play minimum (same order as
     * {@code require-terms-consent}). Read by {@code PhoneRegistrationShapeValidator}.
     */
    private boolean requirePhoneRegistration = false;

    /**
     * B516 (AUTH-45) — {@code app.auth.require-verified-phone} (env {@code APP_AUTH_REQUIRE_VERIFIED_PHONE}), default
     * {@code false}. When {@code true}: an APP booking by a CLIENT without a verified phone, and any non-exempt write
     * by a provider without a verified phone, answer 409 {@code PHONE_NOT_VERIFIED} (no provider deadline,
     * AUTH-D12). Flip only after M517 is the Play minimum (same order as {@code require-terms-consent}).
     */
    private boolean requireVerifiedPhone = false;

    public boolean isRequireVerifiedPhone() {
        return requireVerifiedPhone;
    }

    public void setRequireVerifiedPhone(boolean requireVerifiedPhone) {
        this.requireVerifiedPhone = requireVerifiedPhone;
    }

    public boolean isRequireTermsConsent() {
        return requireTermsConsent;
    }

    public void setRequireTermsConsent(boolean requireTermsConsent) {
        this.requireTermsConsent = requireTermsConsent;
    }

    public boolean isRequirePhoneRegistration() {
        return requirePhoneRegistration;
    }

    public void setRequirePhoneRegistration(boolean requirePhoneRegistration) {
        this.requirePhoneRegistration = requirePhoneRegistration;
    }
}
