package com.beautica.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank(message = "Email is required")
        @Email(message = "Email must be valid")
        @Size(max = 255, message = "Email must not exceed 255 characters")
        String email,

        @NotBlank(message = "Password is required")
        @Size(max = 128, message = "Password must not exceed 128 characters")
        String password
) {

    /**
     * Phase 442 audit — the password (and the email, which is PII) must never reach a log line: the default
     * record {@code toString} would print every component, including {@code password}.
     */
    @Override
    public String toString() {
        return "LoginRequest[]";
    }
}
