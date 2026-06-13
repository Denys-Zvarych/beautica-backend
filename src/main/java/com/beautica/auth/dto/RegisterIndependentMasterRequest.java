package com.beautica.auth.dto;

import com.beautica.common.validation.NoDigits;
import com.beautica.common.validation.StrongPassword;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterIndependentMasterRequest(
        @NotBlank(message = "Email is required")
        @Email(message = "Email must be valid")
        @Size(max = 255, message = "Email must not exceed 255 characters")
        String email,

        // Shared @StrongPassword policy — same implementation as RegisterRequest /
        // ResetPasswordRequest so no self-registration path diverges on strength.
        @NotBlank(message = "Password is required")
        @StrongPassword
        String password,

        @NotBlank(message = "First name is required")
        @Size(max = 100, message = "First name must not exceed 100 characters")
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "First name must not contain control characters")
        @NoDigits(message = "First name must not contain a number")
        String firstName,

        @NotBlank(message = "Last name is required")
        @Size(max = 100, message = "Last name must not exceed 100 characters")
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "Last name must not contain control characters")
        @NoDigits(message = "Last name must not contain a number")
        String lastName,

        @NotBlank(message = "Phone number is required")
        @Size(max = 20, message = "Phone number must not exceed 20 characters")
        @Pattern(regexp = "^\\+?[0-9][0-9\\s\\-()]{6,19}$", message = "Phone number contains invalid characters")
        String phoneNumber
) {
}
