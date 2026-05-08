package com.beautica.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.jwt")
public record JwtConfig(
        String secret,
        long accessTokenExpiration,
        long refreshTokenExpiration
) {
    public JwtConfig {
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException(
                    "app.jwt.secret must be at least 32 characters — refusing to start with a weak JWT secret");
        }
    }
}
