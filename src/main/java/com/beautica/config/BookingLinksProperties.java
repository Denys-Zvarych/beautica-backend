package com.beautica.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Phase 375 — public booking-link sharing ({@code GET /api/v1/booking-links/mine}).
 *
 * <p>Binding prefix {@code app.booking-links}:
 * <ul>
 *   <li>{@code share-enabled} (env {@code BOOKING_LINKS_SHARE_ENABLED}) — the kill-switch (D6).
 *       Default {@code false}: prod hides sharing until the website pages
 *       ({@code beautica.app/book/{slug}}, website 008 + 009) render, then the env var is flipped with
 *       no app release. {@code local}, {@code staging} and {@code test} turn it on. When off the
 *       endpoint returns no links and issues no slugs.</li>
 *   <li>{@code web-base-url} — the public website origin the share URL is built on (D5). Validated at
 *       boot: https, a bare host-only origin (no userinfo {@code @}, no port, no path, no trailing
 *       slash) — so {@code base + "/book/" + slug} is always a
 *       well-formed absolute URL.</li>
 * </ul>
 */
@Validated
@ConfigurationProperties(prefix = "app.booking-links")
public class BookingLinksProperties {

    static final String DEFAULT_WEB_BASE_URL = "https://beautica.app";

    private boolean shareEnabled = false;

    @NotBlank
    @Pattern(regexp = "^https://[A-Za-z0-9.-]+$")
    private String webBaseUrl = DEFAULT_WEB_BASE_URL;

    public boolean isShareEnabled() {
        return shareEnabled;
    }

    public void setShareEnabled(boolean shareEnabled) {
        this.shareEnabled = shareEnabled;
    }

    public String getWebBaseUrl() {
        return webBaseUrl;
    }

    public void setWebBaseUrl(String webBaseUrl) {
        this.webBaseUrl = webBaseUrl;
    }
}
