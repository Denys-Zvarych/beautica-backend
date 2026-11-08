package com.beautica.auth.dto;

import com.beautica.auth.Role;

import java.util.UUID;

public record AuthResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        UUID userId,
        String email,
        Role role,
        UUID salonId
) {

    public static AuthResponse of(
            String accessToken,
            String refreshToken,
            UUID userId,
            String email,
            Role role
    ) {
        return new AuthResponse(accessToken, refreshToken, "Bearer", userId, email, role, null);
    }

    public static AuthResponse of(
            String accessToken,
            String refreshToken,
            UUID userId,
            String email,
            Role role,
            UUID salonId
    ) {
        return new AuthResponse(accessToken, refreshToken, "Bearer", userId, email, role, salonId);
    }

    /**
     * Phase 442 audit — the access and refresh tokens (and the email, which is PII) must never reach a log line:
     * the default record {@code toString} would print every component. JSON serialisation uses the accessors and
     * is unaffected.
     */
    @Override
    public String toString() {
        return "AuthResponse[tokenType=" + tokenType + ", userId=" + userId + ", role=" + role
                + ", salonId=" + salonId + "]";
    }
}
