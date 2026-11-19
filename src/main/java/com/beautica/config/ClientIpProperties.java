package com.beautica.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Whether the per-IP rate-limit key may come from the rightmost {@code X-Forwarded-For} entry or must be the socket
 * address only ({@code ClientIpResolver}).
 *
 * <p>Binding {@code app.rate-limit.trust-forwarded-headers}; profile config, no env var. {@code true} in the base
 * config, {@code prod} (inherited by {@code prod,staging}) and {@code test}: behind Railway's edge proxy the socket
 * peer is the proxy, so the header is the only way to tell clients apart. {@code false} in {@code local}, where
 * nothing sits in front of the app and the header is whatever the caller typed.
 */
@ConfigurationProperties(prefix = "app.rate-limit")
public record ClientIpProperties(@DefaultValue("true") boolean trustForwardedHeaders) {

    /** The production rule: trust the proxy header. Used by the filter's test-only constructors. */
    public static ClientIpProperties trustingForwardedHeaders() {
        return new ClientIpProperties(true);
    }
}
