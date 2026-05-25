package com.beautica.auth.dto;

import com.beautica.common.validation.StrongPassword;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record InviteAcceptRequest(
        @NotBlank(message = "Token is required")
        @Size(max = 200, message = "Token exceeds maximum allowed length")
        String token,

        // Fix MEDIUM-3: @Size(min=12) allowed dictionary passwords like "aaaaaaaaaaaa".
        // Replaced with @StrongPassword (same policy as RegisterRequest and
        // ResetPasswordRequest) so all three auth paths enforce identical strength rules
        // and can never diverge on a future policy change.
        @NotBlank(message = "Password is required")
        @StrongPassword
        String password,

        @NotBlank(message = "First name is required")
        @Size(max = 100) String firstName,

        @NotBlank(message = "Last name is required")
        @Size(max = 100) String lastName,

        @Size(max = 20)  String phoneNumber
) {}
