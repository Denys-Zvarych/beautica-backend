package com.beautica.auth.dto;

import com.beautica.common.validation.StrongPassword;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "Email is required")
        @Email(message = "Email must be valid")
        @Size(max = 255, message = "Email must not exceed 255 characters")
        String email,

        // Length + common-password policy lives in @StrongPassword, shared verbatim with
        // ResetPasswordRequest so the register and reset paths can never diverge.
        @NotBlank(message = "Password is required")
        @StrongPassword
        String password,

        @NotNull(message = "Role is required")
        SelfRegistrationRole role,

        @NotBlank(message = "First name is required")
        @Size(max = 100, message = "First name must not exceed 100 characters")
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "First name must not contain control characters")
        String firstName,

        @NotBlank(message = "Last name is required")
        @Size(max = 100, message = "Last name must not exceed 100 characters")
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "Last name must not contain control characters")
        String lastName,

        @Size(max = 20, message = "Phone number must not exceed 20 characters")
        @Pattern(regexp = "^[+\\d\\s\\-()]*$", message = "Phone number contains invalid characters")
        String phoneNumber,

        @Size(max = 255, message = "Business name must not exceed 255 characters")
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "Business name must not contain control characters")
        String businessName
) {
}
