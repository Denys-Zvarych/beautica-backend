package com.beautica.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RefreshRequest(
        @NotBlank(message = "Refresh token is required")
        @Size(max = 512, message = "Refresh token must not exceed 512 characters")
        String refreshToken
) {
}
