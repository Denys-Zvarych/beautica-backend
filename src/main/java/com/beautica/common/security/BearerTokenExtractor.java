package com.beautica.common.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;

/**
 * Extracts the raw bearer token string from the {@code Authorization} header.
 *
 * <p>Shared by {@link com.beautica.auth.JwtAuthenticationFilter} (which validates and
 * authenticates every request) and any controller that additionally needs the raw
 * token itself — e.g. {@code AuthController#logout}, which needs the current access
 * token's {@code jti} to denylist it on logout. Centralising the "Bearer " prefix
 * parsing here means it exists in exactly one place; it was previously duplicated as
 * a private method on the filter.
 */
public final class BearerTokenExtractor {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private BearerTokenExtractor() {
    }

    /**
     * @return the raw token following the {@code Bearer } prefix, or {@code null} if
     * the {@code Authorization} header is absent, blank, or does not use the
     * {@code Bearer} scheme.
     */
    public static String extract(HttpServletRequest request) {
        String header = request.getHeader(AUTHORIZATION_HEADER);
        if (StringUtils.hasText(header) && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length());
        }
        return null;
    }
}
