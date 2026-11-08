package com.beautica.common.validation;

import com.beautica.config.AuthConsentProperties;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Validator behind {@link TermsConsentComplete}. Under Spring the {@link AuthConsentProperties} bean is injected
 * (Spring's validator factory autowires validators, as for {@link PolicyVersionDateValidator}); a slice without
 * it, or plain Bean Validation, falls back to the tolerant default ({@code require-terms-consent = false}).
 */
public class TermsConsentCompleteValidator implements ConstraintValidator<TermsConsentComplete, TermsConsentPayload> {

    static final String TERMS_ACCEPTED = "termsAccepted";
    static final String TERMS_VERSION = "termsVersion";
    static final String PRIVACY_POLICY_VERSION = "privacyPolicyVersion";
    static final String MSG_TERMS_REQUIRED = "Terms must be accepted";
    static final String MSG_TERMS_VERSION_REQUIRED = "Terms version is required";
    static final String MSG_PRIVACY_VERSION_REQUIRED = "Privacy policy version is required";

    private final boolean requireConsent;

    /** Plain Bean Validation (no Spring): tolerant default. */
    public TermsConsentCompleteValidator() {
        this(false);
    }

    /** Explicit mode — unit tests. */
    public TermsConsentCompleteValidator(boolean requireConsent) {
        this.requireConsent = requireConsent;
    }

    @Autowired
    public TermsConsentCompleteValidator(ObjectProvider<AuthConsentProperties> properties) {
        AuthConsentProperties bound = properties.getIfAvailable();
        this.requireConsent = bound != null && bound.isRequireTermsConsent();
    }

    @Override
    public boolean isValid(TermsConsentPayload value, ConstraintValidatorContext context) {
        if (value == null) {
            return true; // @Valid on a null body is reported elsewhere
        }
        if (!value.hasAnyConsentField() && !requireConsent) {
            return true; // legacy build inside the tolerant window
        }
        boolean valid = true;
        context.disableDefaultConstraintViolation();
        if (!Boolean.TRUE.equals(value.termsAccepted())) {
            valid = report(context, TERMS_ACCEPTED, MSG_TERMS_REQUIRED);
        }
        if (value.termsVersion() == null) {
            valid = report(context, TERMS_VERSION, MSG_TERMS_VERSION_REQUIRED);
        }
        if (value.privacyPolicyVersion() == null) {
            valid = report(context, PRIVACY_POLICY_VERSION, MSG_PRIVACY_VERSION_REQUIRED);
        }
        return valid;
    }

    private static boolean report(ConstraintValidatorContext context, String field, String message) {
        context.buildConstraintViolationWithTemplate(message).addPropertyNode(field).addConstraintViolation();
        return false;
    }
}
