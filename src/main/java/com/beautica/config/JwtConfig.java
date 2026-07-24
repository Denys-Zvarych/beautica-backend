package com.beautica.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

@ConfigurationProperties(prefix = "app.jwt")
public record JwtConfig(
        String secret,
        long accessTokenExpiration,
        long refreshTokenExpiration
) {
    // Upper bounds guard against a config typo (extra zero, unit confusion) silently
    // shipping an unintended session lifetime. There is no legitimate reason for an
    // access token to outlive a day or a refresh token to outlive a month in this
    // project's threat model.
    private static final long MAX_ACCESS_TOKEN_EXPIRATION_MS = Duration.ofHours(24).toMillis();
    private static final long MAX_REFRESH_TOKEN_EXPIRATION_MS = Duration.ofDays(30).toMillis();

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
        if (accessTokenExpiration <= 0) {
            throw new IllegalStateException(
                    "app.jwt.access-token-expiration must be positive — refusing to start with a "
                    + "non-positive access-token TTL. Current value: " + accessTokenExpiration + " ms.");
        }
        if (accessTokenExpiration > MAX_ACCESS_TOKEN_EXPIRATION_MS) {
            throw new IllegalStateException(
                    "app.jwt.access-token-expiration must not exceed 24 hours ("
                    + MAX_ACCESS_TOKEN_EXPIRATION_MS + " ms) — refusing to start with an unbounded "
                    + "access-token session lifetime. Current value: " + accessTokenExpiration + " ms.");
        }
        if (refreshTokenExpiration <= 0) {
            throw new IllegalStateException(
                    "app.jwt.refresh-token-expiration must be positive — refusing to start with a "
                    + "non-positive refresh-token TTL. Current value: " + refreshTokenExpiration + " ms.");
        }
        if (refreshTokenExpiration > MAX_REFRESH_TOKEN_EXPIRATION_MS) {
            throw new IllegalStateException(
                    "app.jwt.refresh-token-expiration must not exceed 30 days ("
                    + MAX_REFRESH_TOKEN_EXPIRATION_MS + " ms) — refusing to start with an unbounded "
                    + "refresh-token session lifetime. Current value: " + refreshTokenExpiration + " ms.");
        }
    }
}
