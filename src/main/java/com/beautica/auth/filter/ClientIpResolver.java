package com.beautica.auth.filter;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The one client-IP resolution rule behind the per-IP rate-limit buckets, extracted from
 * {@link AuthRateLimitFilter} (phase 382 D5) so the Turnstile check on {@code POST /book/otp/send} sees the
 * same address the OTP bucket charged — never a second resolver that could drift from it.
 */
public final class ClientIpResolver {

    /** Longest textual IPv6 address; anything longer is a crafted header and falls back to the socket peer. */
    private static final int MAX_IP_LENGTH = 45;

    private ClientIpResolver() {
    }

    /**
     * The rightmost non-empty {@code X-Forwarded-For} entry (appended by Railway's trusted proxy, so it cannot
     * be spoofed by the caller), else the socket peer. An entry over {@value #MAX_IP_LENGTH} chars is replaced
     * by the socket peer.
     */
    public static String resolve(HttpServletRequest request) {
        String xfwd = request.getHeader("X-Forwarded-For");
        if (xfwd != null && !xfwd.isBlank()) {
            String[] parts = xfwd.split(",");
            // Rightmost entry is appended by Railway's trusted proxy — cannot be spoofed.
            for (int i = parts.length - 1; i >= 0; i--) {
                String part = parts[i].trim();
                if (!part.isEmpty()) {
                    return part.length() > MAX_IP_LENGTH ? request.getRemoteAddr() : part;
                }
            }
        }
        return request.getRemoteAddr();
    }
}
