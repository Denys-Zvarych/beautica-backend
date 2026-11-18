package com.beautica.auth.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
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

        @Schema(description = "Legacy (pre-B516): the account email. Prefer identifier.", nullable = true)
        @Email(message = "Email must be valid")
        @Size(max = 255, message = "Email must not exceed 255 characters")
        String email,

        @NotBlank(message = "Code is required")
        @Size(min = 6, max = 6, message = "Code must be 6 digits")
        @Pattern(regexp = "^[0-9]{6}$", message = "Code must be exactly 6 digits")
        String code,

        @Schema(description = "B516: the same phone or email sent to forgot-password.", nullable = true,
                maxLength = 255)
        @Size(max = 255, message = "Identifier must not exceed 255 characters")
        @Pattern(regexp = "^[^\\p{Cntrl}]*$",
                message = "Identifier contains invalid characters")
        String identifier

) {

    /** Pre-B516 shape. */
    public VerifyPasswordResetOtpRequest(String email, String code) {
        this(email, code, null);
    }

    /** {@code identifier} when given, else the legacy {@code email}; trimmed. */
    public String resolvedIdentifier() {
        String value = identifier != null && !identifier.isBlank() ? identifier : email;
        return value == null ? null : value.strip();
    }

    @JsonIgnore
    @AssertTrue(message = "Phone or email is required")
    public boolean isIdentifierPresent() {
        String value = resolvedIdentifier();
        return value != null && !value.isEmpty();
    }

    /**
     * Phase 442 audit — the OTP code (and the email, which is PII) must never reach a log line: the default record {@code toString}
     * would print every component.
     */
    @Override
    public String toString() {
        return "VerifyPasswordResetOtpRequest[]";
    }
}
