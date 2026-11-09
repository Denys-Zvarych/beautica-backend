package com.beautica.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Phase 442 — class-level rule on a {@link TermsConsentPayload}: the consent triple is either complete
 * ({@code termsAccepted = true} plus both versions) or, only while {@code app.auth.require-terms-consent} is
 * {@code false}, entirely absent. Partial or false consent is always invalid. Violations are reported on the
 * offending field so the 400 {@code errors} map names it.
 */
@Documented
@Constraint(validatedBy = TermsConsentCompleteValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface TermsConsentComplete {

    String message() default "Terms and privacy policy must be accepted";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
