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
 * The dev default ({@code changeme-dev-only}) lives only in {@code application-local.yml} /
 * {@code application-test.yml}; prod/staging map it in {@code application-prod.yml} and
 * {@code EnvironmentFingerprintGuard} rejects blank, default or &lt; 32-char values.
 *
 * <p>Both keys are {@link String#strip() stripped} once, at binding: the guard validates the stripped value, so the
 * filter must compare that same value (a key pasted into Railway with a trailing newline would otherwise pass the
 * guard and then match no request).
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

    /**
     * B501 — optional catalog-only key ({@code ${INTERNAL_CATALOG_KEY}}, blank = disabled). It opens ONLY
     * {@code /api/v1/internal/catalog/**} (SVC-54); it is the only key the website admin Worker holds (track D2/D3).
     * {@code EnvironmentFingerprintGuard} requires it, when set on prod/staging, to be &gt;= 32 chars and to differ
     * from {@link #getInternalApiKey()}.
     */
    private String internalCatalogKey;

    public String getInternalApiKey() {
        return internalApiKey;
    }

    public void setInternalApiKey(String internalApiKey) {
        this.internalApiKey = stripOrNull(internalApiKey);
    }

    public String getInternalCatalogKey() {
        return internalCatalogKey;
    }

    public void setInternalCatalogKey(String internalCatalogKey) {
        this.internalCatalogKey = stripOrNull(internalCatalogKey);
    }

    private static String stripOrNull(String value) {
        return value == null ? null : value.strip();
    }
}
