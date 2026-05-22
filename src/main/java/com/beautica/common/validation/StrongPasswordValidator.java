package com.beautica.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.Locale;
import java.util.Set;

/**
 * Shared implementation backing {@link StrongPassword}. Used by both the registration
 * and the password-reset DTOs so the two flows can never diverge on password strength.
 *
 * <p>Rules:
 * <ul>
 *   <li>Length 8–128 (the historical bound; below 8 is rejected, above 128 guards the
 *       BCrypt 72-byte-relevant input and oversized payloads).</li>
 *   <li>Case-insensitive denylist of the most trivially-guessable values (OWASP ASVS 2.1
 *       common-password rejection). Kept deliberately small and in-process — this is a
 *       last-line guard against the worst offenders, not a substitute for a breach-corpus
 *       check.</li>
 * </ul>
 *
 * <p>{@code null} / blank values return {@code true} (valid) — Bean Validation skips them
 * here so {@code @NotBlank} owns the "required" message. This matches the matrix in §A of
 * the Anti-Bug Playbook (pair the format constraint with {@code @NotBlank}).
 */
public class StrongPasswordValidator implements ConstraintValidator<StrongPassword, String> {

    static final int MIN_LENGTH = 8;
    static final int MAX_LENGTH = 128;

    /**
     * Lower-cased denylist of trivially-weak passwords. Compared case-insensitively, so
     * "Password" and "PASSWORD" are both rejected via a single lower-cased lookup.
     */
    static final Set<String> COMMON_PASSWORDS = Set.of(
            "password",
            "password1",
            "password123",
            "passw0rd",
            "12345678",
            "123456789",
            "1234567890",
            "qwerty123",
            "qwertyuiop",
            "11111111",
            "00000000",
            "iloveyou",
            "admin123",
            "letmein1",
            "welcome1",
            "abc12345",
            "changeme"
    );

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        // Null/blank handled by @NotBlank — skip here so messages stay distinct.
        if (value == null || value.isBlank()) {
            return true;
        }
        if (value.length() < MIN_LENGTH || value.length() > MAX_LENGTH) {
            return false;
        }
        return !COMMON_PASSWORDS.contains(value.toLowerCase(Locale.ROOT));
    }
}
