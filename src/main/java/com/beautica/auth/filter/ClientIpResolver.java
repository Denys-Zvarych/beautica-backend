package com.beautica.auth.filter;

import com.beautica.config.ClientIpProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.Collections;
import java.util.Enumeration;

/**
 * The one client-IP resolution rule behind the per-IP rate-limit buckets, extracted from
 * {@link AuthRateLimitFilter} (phase 382 D5) so the login lockout key, the Turnstile {@code remoteip} fallback and
 * the Telegram webhook reject throttle see the same address the buckets charged — never a second resolver that could
 * drift from it. Holds the bound {@code app.rate-limit.trust-forwarded-headers} flag, so no caller can pick the rule.
 *
 * <p>Trust off (the {@code local} profile): the socket peer only — no proxy in front, so every header is
 * caller-typed.
 *
 * <p>Trust on: the rightmost non-empty {@code X-Forwarded-For} entry when it is a valid IP literal of at most 45
 * chars ({@link AuthRateLimitFilter#parseIpLiteral}), else the socket peer. The header is trusted ONLY because
 * exactly one Railway edge-proxy hop sits in front of the app and appends the address it saw as the rightmost
 * entry, so the caller controls only entries to its left. {@code X-Real-IP} is never read: it may be passed through
 * from the caller at Railway's edge. A second proxy or CDN layer in front would make the rightmost entry that layer's
 * address. Exception: a request reaching the app over Railway private networking ({@code *.railway.internal})
 * bypasses the edge proxy, so the header is entirely caller-controlled there.
 *
 * <p>A header-derived address is returned in canonical form ({@link InetAddress#getHostAddress()}), so one address
 * maps to one bucket key whatever its notation.
 */
@Component
public class ClientIpResolver {

    private static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";

    private final boolean trustForwardedHeaders;

    public ClientIpResolver(ClientIpProperties properties) {
        this.trustForwardedHeaders = properties.trustForwardedHeaders();
    }

    /** @return the client IP for the per-IP buckets under the bound trust rule */
    public String resolve(HttpServletRequest request) {
        if (!trustForwardedHeaders) {
            return request.getRemoteAddr();
        }
        InetAddress forwarded = AuthRateLimitFilter.parseIpLiteral(rightmostForwardedFor(request));
        return forwarded != null ? forwarded.getHostAddress() : request.getRemoteAddr();
    }

    /** The rightmost non-empty {@code X-Forwarded-For} entry, or {@code null} when there is none. */
    private static String rightmostForwardedFor(HttpServletRequest request) {
        // Every header line, not just the first: an edge that appends a separate line would otherwise leave a
        // caller-supplied first line in control of the key.
        Enumeration<String> lines = request.getHeaders(FORWARDED_FOR_HEADER);
        if (lines == null || !lines.hasMoreElements()) {
            return null;
        }
        String xfwd = String.join(",", Collections.list(lines));
        String[] parts = xfwd.split(",");
        for (int i = parts.length - 1; i >= 0; i--) {
            String part = parts[i].trim();
            if (!part.isEmpty()) {
                return part;
            }
        }
        return null;
    }
}
