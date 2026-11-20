package com.beautica.user.email;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * B516 — body of {@code POST /api/v1/users/me/email/verify}: the email-verification fields (same rules) plus the
 * {@code currentPassword} the service requires for an email ADD or CHANGE (AUTH-D19). Optional at the boundary so a
 * missing one is the same 400 {@code CURRENT_PASSWORD_INVALID} as a wrong one (never a validation 400 that skips the
 * attempt count).
 */
public record NotificationEmailVerifyRequest(
        @NotBlank(message = "Email is required")
        @Email(message = "Email must be valid")
        @Size(max = 255, message = "Email must not exceed 255 characters")
        String email,
        @NotBlank(message = "Code is required")
        @Size(min = 6, max = 6, message = "Code must be 6 digits")
        @Pattern(regexp = "^[0-9]{6}$", message = "Code must be exactly 6 digits")
        String code,
        @Size(max = 128, message = "Password must not exceed 128 characters")
        String currentPassword
) {
    /** The email (PII), the code and the password must never reach a log line. */
    @Override
    public String toString() {
        return "NotificationEmailVerifyRequest[]";
    }
}
