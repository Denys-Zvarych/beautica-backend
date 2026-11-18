package com.beautica.user.phone;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * B516 — body of {@code POST /api/v1/users/me/phone/otp/verify}: the registration verify fields (same rules) plus the
 * optional {@code currentPassword}, required by the service for a phone CHANGE and ignored for a first add (AUTH-D19).
 * Optional here so a first add keeps working without it; a missing one on a change is answered like a wrong one.
 */
public record PhoneChangeVerifyRequest(
        @NotBlank(message = "Phone is required")
        @Size(max = 32, message = "Phone must not exceed 32 characters")
        @Pattern(regexp = "^[+\\d\\s\\-().]*$", message = "Phone contains invalid characters")
        String phone,
        @NotBlank(message = "Code is required")
        @Pattern(regexp = "[0-9]{6}", message = "Code must be 6 digits")
        String code,
        @Size(max = 128, message = "Password must not exceed 128 characters")
        String currentPassword
) {
    /** The phone (PII), the code and the password must never reach a log line. */
    @Override
    public String toString() {
        return "PhoneChangeVerifyRequest[]";
    }
}
