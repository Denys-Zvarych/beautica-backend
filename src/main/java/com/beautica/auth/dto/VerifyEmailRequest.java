package com.beautica.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record VerifyEmailRequest(
        @NotBlank(message = "Email is required")
        @Email(message = "Email must be valid")
        @Size(max = 255)
        String email,

        @NotBlank(message = "Code is required")
        @Size(min = 6, max = 6, message = "Code must be 6 digits")
        @Pattern(regexp = "^[0-9]{6}$", message = "Code must be exactly 6 digits")
        String code
) {
}
