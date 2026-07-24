package com.beautica.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/auth/verify-password-reset-otp}.
 *
 * <p>Bean Validation mirrors {@link VerifyEmailRequest} exactly — same field shapes, same
 * §A rationale (blank/oversized-payload guard on {@code email}; exact-6-digit charset guard
 * on {@code code} so a non-numeric or wrong-length submission is rejected at the controller
 * boundary, before it ever reaches {@code PasswordResetOtpProcessor}'s hash comparison).
 */
public record VerifyPasswordResetOtpRequest(

        @NotBlank(message = "Email is required")
        @Email(message = "Email must be valid")
        @Size(max = 255, message = "Email must not exceed 255 characters")
        String email,

        @NotBlank(message = "Code is required")
        @Size(min = 6, max = 6, message = "Code must be 6 digits")
        @Pattern(regexp = "^[0-9]{6}$", message = "Code must be exactly 6 digits")
        String code

) {
}
