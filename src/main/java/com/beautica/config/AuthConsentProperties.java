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

    public boolean isRequireTermsConsent() {
        return requireTermsConsent;
    }

    public void setRequireTermsConsent(boolean requireTermsConsent) {
        this.requireTermsConsent = requireTermsConsent;
    }
}
