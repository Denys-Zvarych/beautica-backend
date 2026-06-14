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
 * Bean Validation constraint rejecting any value that contains a numeric digit.
 *
 * <p>Applied to the person-name fields ({@code firstName}, {@code lastName}) across the
 * registration, invite-accept and master-profile-update DTOs so a name can never carry a
 * number. The rule is intentionally narrow: the ONLY thing forbidden is a digit. Letters of
 * any script (Cyrillic, Latin, …), hyphens, apostrophes (ASCII {@code '} and typographic
 * {@code ’}) and spaces all remain valid — control-character and length policy stay with the
 * existing {@code @Pattern}/{@code @Size}/{@code @NotBlank} constraints on the same field.
 *
 * <p>"Digit" means any Unicode decimal digit ({@code \p{Nd}} / {@code \d}), so other-script
 * digits (Arabic-Indic, Devanagari, …) are rejected alongside ASCII {@code 0-9}.
 *
 * <p>{@code null} / blank are intentionally NOT rejected here — pair with {@code @NotBlank}
 * so the "required" message stays distinct from the "contains a digit" message (§A of the
 * Anti-Bug Playbook: format constraints skip null and let {@code @NotBlank} own emptiness).
 */
@Documented
@Constraint(validatedBy = NoDigitsValidator.class)
@Target({FIELD, PARAMETER, RECORD_COMPONENT, ANNOTATION_TYPE})
@Retention(RUNTIME)
public @interface NoDigits {

    String message() default "Must not contain a number";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
