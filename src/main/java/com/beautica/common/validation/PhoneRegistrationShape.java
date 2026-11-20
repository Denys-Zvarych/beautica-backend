package com.beautica.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * B514 (AUTH-02, AUTH-D11, AUTH-43) — class-level rule on a {@link PhoneRegistrationPayload}:
 * <ul>
 *   <li>with a {@code registrationTicket}: no {@code email} (email is added and confirmed later, B516);
 *       {@code phoneNumber} and {@code lastName} are optional;</li>
 *   <li>without one: rejected while {@code app.auth.require-phone-registration} is {@code true}; otherwise the legacy
 *       shape — {@code phoneNumber} (and for self-registration {@code email} + {@code lastName}) required.</li>
 * </ul>
 * Violations are reported on the offending field, with the same messages the field-level {@code @NotBlank}s used
 * before, so the 400 {@code errors} map is unchanged for legacy builds.
 */
@Documented
@Constraint(validatedBy = PhoneRegistrationShapeValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface PhoneRegistrationShape {

    String message() default "Registration request is incomplete";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
