package com.beautica.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;

@ConfigurationProperties(prefix = "app.jwt")
public record JwtConfig(
        String secret,
        long accessTokenExpiration,
        long refreshTokenExpiration
) {
    public JwtConfig {
        if (secret == null) {
            throw new IllegalStateException(
                    "app.jwt.secret must be set — refusing to start with no JWT secret");
        }
        int byteLength = secret.getBytes(StandardCharsets.UTF_8).length;
        if (byteLength < 32) {
            throw new IllegalStateException(
                    "app.jwt.secret must be at least 32 bytes (UTF-8 encoded) — "
                    + "refusing to start with a weak JWT secret. "
                    + "Current length: " + byteLength + " bytes.");
        }
        if (secret.startsWith("REPLACE_ME")) {
            throw new IllegalStateException(
                    "JWT secret must be replaced — current value is a placeholder");
        }
    }
}
