package com.beautica.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Phase 417 — Cloudflare edge-cache purge for deleted media ({@code CloudflareCachePurger}).
 *
 * <p>Binding prefix {@code app.cloudflare-cdn}:
 * <ul>
 *   <li>{@code zone-id} (env {@code CLOUDFLARE_CDN_ZONE_ID}) and {@code purge-token} (env
 *       {@code CLOUDFLARE_CDN_PURGE_TOKEN}, a Cache-Purge-only API token for zone beautica.app). Either one blank
 *       means purge is DISABLED — never a boot failure (local / test / CI run without them).</li>
 *   <li>{@code api-base-url} — the Cloudflare API root. Any value other than {@value #DEFAULT_API_BASE_URL} is
 *       rejected at boot unless {@code allow-custom-api-base-url=true} (tests point it at WireMock). Mirrors R2's
 *       {@code allow-custom-endpoint} SSRF guard: a mis-set env var must not send the Bearer token elsewhere.</li>
 *   <li>{@code connect-timeout} / {@code read-timeout} — validated positive ({@link OutboundTimeouts}).</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "app.cloudflare-cdn")
public record CloudflareCdnProperties(
        String zoneId,
        String purgeToken,
        String apiBaseUrl,
        boolean allowCustomApiBaseUrl,
        Duration connectTimeout,
        Duration readTimeout
) {

    public static final String DEFAULT_API_BASE_URL = "https://api.cloudflare.com/client/v4";
    static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(2);
    static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(5);

    public CloudflareCdnProperties {
        zoneId = zoneId == null ? "" : zoneId.trim();
        purgeToken = purgeToken == null ? "" : purgeToken.trim();
        apiBaseUrl = apiBaseUrl == null || apiBaseUrl.isBlank() ? DEFAULT_API_BASE_URL : stripTrailingSlash(apiBaseUrl.trim());
        connectTimeout = OutboundTimeouts.requirePositive("app.cloudflare-cdn.connect-timeout",
                connectTimeout == null ? DEFAULT_CONNECT_TIMEOUT : connectTimeout);
        readTimeout = OutboundTimeouts.requirePositive("app.cloudflare-cdn.read-timeout",
                readTimeout == null ? DEFAULT_READ_TIMEOUT : readTimeout);
        if (!allowCustomApiBaseUrl && !DEFAULT_API_BASE_URL.equals(apiBaseUrl)) {
            throw new IllegalStateException("app.cloudflare-cdn.api-base-url must be " + DEFAULT_API_BASE_URL
                    + " (set app.cloudflare-cdn.allow-custom-api-base-url=true to override)");
        }
    }

    /** {@code true} when both the zone id and the purge token are set. */
    public boolean purgeConfigured() {
        return !zoneId.isEmpty() && !purgeToken.isEmpty();
    }

    /** Never prints the token — a record's generated {@code toString} would. */
    @Override
    public String toString() {
        return "CloudflareCdnProperties[zoneId=" + zoneId + ", purgeToken=" + (purgeToken.isEmpty() ? "" : "[redacted]")
                + ", apiBaseUrl=" + apiBaseUrl + ", allowCustomApiBaseUrl=" + allowCustomApiBaseUrl
                + ", connectTimeout=" + connectTimeout + ", readTimeout=" + readTimeout + "]";
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
