package com.beautica.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.RECORD_COMPONENT;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Bean Validation constraint enforcing the shared Beautica password policy.
 *
 * <p>One implementation, three call sites — applied to {@code RegisterRequest.password},
 * {@code RegisterIndependentMasterRequest.password} and {@code ResetPasswordRequest.newPassword}
 * so a password accepted at registration can always be set via the reset path and
 * vice-versa (no policy drift between the flows).
 *
 * <p>Policy (see {@link StrongPasswordValidator}): length 8–128, at least one digit and at
 * least one uppercase ASCII letter (a strict superset of the mobile registration policy),
 * plus a small denylist of trivially-guessable values (OWASP ASVS 2.1 common-password
 * rejection). The length bound lives here, not as a separate {@code @Size}, so callers
 * cannot accidentally diverge the flows by changing one annotation and not the other.
 *
 * <p>{@code null} / blank are intentionally NOT rejected here — pair with {@code @NotBlank}
 * so the "required" message stays distinct from the "too weak" message.
 */
@Documented
@Constraint(validatedBy = StrongPasswordValidator.class)
@Target({FIELD, PARAMETER, RECORD_COMPONENT, ANNOTATION_TYPE})
@Retention(RUNTIME)
public @interface StrongPassword {

    String message() default "Password must be 8-128 characters, include a digit and an "
            + "uppercase letter, and not be a commonly used password";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
