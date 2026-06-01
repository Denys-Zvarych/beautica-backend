package com.beautica.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Typed configuration for the internal API key that protects
 * {@code /api/v1/internal/**} endpoints.
 *
 * <p>Binding prefix: {@code app.internal-api-key}.
 * Source: {@code ${INTERNAL_API_KEY}} — supply via Railway environment variable.
 * Local default ({@code changeme-dev-only}) is acceptable for non-prod environments.
 */
@Validated
@ConfigurationProperties(prefix = "app")
public class InternalApiKeyProperties {

    /**
     * Secret API key for the internal management endpoints.
     * Callers must send this value in the {@code X-Internal-Key} header.
     */
    @NotBlank
    private String internalApiKey;

    public String getInternalApiKey() {
        return internalApiKey;
    }

    public void setInternalApiKey(String internalApiKey) {
        this.internalApiKey = internalApiKey;
    }
}
