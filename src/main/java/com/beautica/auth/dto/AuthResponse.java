package com.beautica.auth.dto;

import com.beautica.auth.Role;

import java.util.UUID;

public record AuthResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        UUID userId,
        String email,
        Role role
) {

    public static AuthResponse of(
            String accessToken,
            String refreshToken,
            UUID userId,
            String email,
            Role role
    ) {
        return new AuthResponse(accessToken, refreshToken, "Bearer", userId, email, role);
    }
}
