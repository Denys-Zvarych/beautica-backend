package com.beautica.common.validation;

import com.beautica.config.AuthConsentProperties;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Validator behind {@link PhoneRegistrationShape}. Under Spring the {@link AuthConsentProperties} bean is injected
 * (as {@link TermsConsentCompleteValidator}); plain Bean Validation or a slice without it falls back to the tolerant
 * default ({@code require-phone-registration = false}).
 */
public class PhoneRegistrationShapeValidator
        implements ConstraintValidator<PhoneRegistrationShape, PhoneRegistrationPayload> {

    static final String MSG_TICKET_REQUIRED = "Registration ticket is required";
    static final String MSG_EMAIL_WITH_TICKET = "Email is not accepted with a registration ticket";
    static final String MSG_EMAIL_REQUIRED = "Email is required";
    static final String MSG_PHONE_REQUIRED = "Phone number is required";
    static final String MSG_LAST_NAME_REQUIRED = "Last name is required";

    private final boolean requirePhoneRegistration;

    /** Plain Bean Validation (no Spring): tolerant default. */
    public PhoneRegistrationShapeValidator() {
        this(false);
    }

    /** Explicit mode — unit tests. */
    public PhoneRegistrationShapeValidator(boolean requirePhoneRegistration) {
        this.requirePhoneRegistration = requirePhoneRegistration;
    }

    @Autowired
    public PhoneRegistrationShapeValidator(ObjectProvider<AuthConsentProperties> properties) {
        AuthConsentProperties bound = properties.getIfAvailable();
        this.requirePhoneRegistration = bound != null && bound.isRequirePhoneRegistration();
    }

    @Override
    public boolean isValid(PhoneRegistrationPayload value, ConstraintValidatorContext context) {
        if (value == null) {
            return true; // @Valid on a null body is reported elsewhere
        }
        context.disableDefaultConstraintViolation();
        if (value.hasRegistrationTicket()) {
            return value.email() == null || report(context, "email", MSG_EMAIL_WITH_TICKET);
        }
        if (requirePhoneRegistration) {
            return report(context, "registrationTicket", MSG_TICKET_REQUIRED);
        }
        return legacyShapeValid(value, context);
    }

    private static boolean legacyShapeValid(PhoneRegistrationPayload value, ConstraintValidatorContext context) {
        boolean valid = true;
        if (value.legacyShapeNeedsEmailAndLastName()) {
            if (isBlank(value.email())) {
                valid = report(context, "email", MSG_EMAIL_REQUIRED);
            }
            if (isBlank(value.lastName())) {
                valid = report(context, "lastName", MSG_LAST_NAME_REQUIRED);
            }
        }
        if (isBlank(value.phoneNumber())) {
            valid = report(context, "phoneNumber", MSG_PHONE_REQUIRED);
        }
        return valid;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean report(ConstraintValidatorContext context, String field, String message) {
        context.buildConstraintViolationWithTemplate(message).addPropertyNode(field).addConstraintViolation();
        return false;
    }
}
