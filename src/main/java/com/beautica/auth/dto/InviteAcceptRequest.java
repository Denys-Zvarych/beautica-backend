package com.beautica.auth.dto;

import com.beautica.common.validation.StrongPassword;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record InviteAcceptRequest(
        // max=512 mirrors invite_tokens.token VARCHAR(512) — the previous cap of 200
        // was under-sized and would reject valid HMAC-SHA256 hex tokens.
        @NotBlank(message = "Token is required")
        @Size(max = 512, message = "Token exceeds maximum allowed length")
        String token,

        // Fix MEDIUM-3: @Size(min=12) allowed dictionary passwords like "aaaaaaaaaaaa".
        // Replaced with @StrongPassword (same policy as RegisterRequest and
        // ResetPasswordRequest) so all three auth paths enforce identical strength rules
        // and can never diverge on a future policy change.
        @NotBlank(message = "Password is required")
        @StrongPassword
        String password,

        @NotBlank(message = "First name is required")
        @Size(max = 100)
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "First name must not contain control characters")
        String firstName,

        @NotBlank(message = "Last name is required")
        @Size(max = 100)
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "Last name must not contain control characters")
        String lastName,

        @Size(max = 20)
        @Pattern(regexp = "^[+\\d\\s\\-()]*$", message = "Phone number contains invalid characters")
        String phoneNumber
) {}
