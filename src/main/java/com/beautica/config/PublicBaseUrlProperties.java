package com.beautica.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Typed config for the platform's externally-reachable base URL.
 *
 * <p>Binding key: {@code app.public-base-url}. Used to build links to pages served by THIS backend (e.g. the
 * one-click moderation action links). Distinct from {@code app.frontend.base-url} (the mobile/web app origin). The
 * catalog-request emails no longer use it (B545: they link to the portal on {@code app.frontend.base-url}).
 */
@Validated
@ConfigurationProperties(prefix = "app")
public class PublicBaseUrlProperties {

    @NotBlank
    @Pattern(regexp = "^https?://[^/?#\\s]+$")
    private String publicBaseUrl;

    public String getPublicBaseUrl() {
        return publicBaseUrl;
    }

    public void setPublicBaseUrl(String publicBaseUrl) {
        this.publicBaseUrl = publicBaseUrl;
    }
}
