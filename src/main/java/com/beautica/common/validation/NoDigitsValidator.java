package com.beautica.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.regex.Pattern;

/**
 * Shared implementation backing {@link NoDigits}. Used by the person-name fields on the
 * registration, invite-accept and master-profile-update DTOs.
 *
 * <p>Rule: the value is invalid if (and only if) it contains at least one Unicode decimal
 * digit ({@code \p{Nd}}). Everything else — letters of any script, hyphen, apostrophe,
 * spaces — is allowed; this validator deliberately does not duplicate the length or
 * control-character checks that the sibling {@code @Size}/{@code @Pattern} constraints own.
 *
 * <p>{@code null} / blank values return {@code true} (valid) — Bean Validation skips them
 * here so {@code @NotBlank} owns the "required" message (§A of the Anti-Bug Playbook).
 */
public class NoDigitsValidator implements ConstraintValidator<NoDigits, String> {

    /**
     * Precompiled once — matches a single Unicode decimal digit anywhere in the input.
     * Used with {@link java.util.regex.Matcher#find()} (not {@code matches()} with a
     * {@code .*} wrapper) per the performance playbook: {@code find()} short-circuits on the
     * first digit and avoids backtracking over the whole string.
     */
    private static final Pattern HAS_DIGIT = Pattern.compile("\\p{Nd}");

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        // Null/blank handled by @NotBlank — skip here so messages stay distinct.
        if (value == null || value.isBlank()) {
            return true;
        }
        return !HAS_DIGIT.matcher(value).find();
    }
}
