package com.beautica.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record InviteAcceptRequest(
        @NotBlank(message = "Token is required")
        @Size(max = 200, message = "Token exceeds maximum allowed length")
        String token,

        @NotBlank(message = "Password is required")
        @Size(min = 12, max = 128, message = "Password must be between 12 and 128 characters")
        String password,

        @NotBlank(message = "First name is required")
        @Size(max = 100) String firstName,

        @NotBlank(message = "Last name is required")
        @Size(max = 100) String lastName,

        @Size(max = 20)  String phoneNumber
) {}
