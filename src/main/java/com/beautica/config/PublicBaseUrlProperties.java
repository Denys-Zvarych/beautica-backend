package com.beautica.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Typed config for the platform's externally-reachable base URL.
 *
 * <p>Binding key: {@code app.public-base-url}. Used to build the admin approval
 * link ({@code {public-base-url}/api/v1/service-categories/requests/review?token=...})
 * embedded in the category-request notification email. Distinct from
 * {@code app.frontend.base-url} (the mobile/web app origin) because the approval
 * page is served by THIS backend, not the frontend.
 */
@Validated
@ConfigurationProperties(prefix = "app")
public class PublicBaseUrlProperties {

    @NotBlank
    private String publicBaseUrl;

    public String getPublicBaseUrl() {
        return publicBaseUrl;
    }

    public void setPublicBaseUrl(String publicBaseUrl) {
        this.publicBaseUrl = publicBaseUrl;
    }
}
