package com.beautica.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RefreshRequest(
        @NotBlank(message = "Refresh token is required")
        @Size(max = 512, message = "Refresh token must not exceed 512 characters")
        String refreshToken
) {

    /**
     * Phase 442 audit — the refresh token must never reach a log line: the default record {@code toString}
     * would print every component.
     */
    @Override
    public String toString() {
        return "RefreshRequest[]";
    }
}
