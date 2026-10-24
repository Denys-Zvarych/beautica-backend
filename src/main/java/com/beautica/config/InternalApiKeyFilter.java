package com.beautica.config;

import com.beautica.common.ApiResponse;
import com.beautica.common.security.ConstantTimeSecrets;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.MessageDigest;

/**
 * Servlet filter that guards all {@code /api/v1/internal/**} endpoints with a
 * static API key carried in the {@code X-Internal-Key} request header.
 *
 * <h2>Authentication mechanism</h2>
 * The filter compares the supplied header value against
 * {@link InternalApiKeyProperties#getInternalApiKey()} using
 * {@link ConstantTimeSecrets#matches} (SHA-256 + {@link MessageDigest#isEqual}) — a constant-time comparison
 * that prevents timing-oracle attacks (anti-bug §B analogue for API keys).
 *
 * <h2>Scope</h2>
 * The filter short-circuits on all paths that do NOT start with
 * {@code /api/v1/internal} so it adds zero overhead to normal JWT-protected
 * endpoints.
 *
 * <h2>Security config</h2>
 * {@code /api/v1/internal/**} is declared {@code permitAll()} in
 * {@link SecurityConfig} — the JWT filter skips these paths. This filter is
 * the sole authentication mechanism for the internal API.
 */
@Component
@RequiredArgsConstructor
public class InternalApiKeyFilter extends OncePerRequestFilter {

    private static final String HEADER_NAME = "X-Internal-Key";
    private static final String INTERNAL_PATH_PREFIX = "/api/v1/internal";

    private final InternalApiKeyProperties internalApiKeyProperties;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getServletPath().startsWith(INTERNAL_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String suppliedKey = request.getHeader(HEADER_NAME);

        String configuredKey = internalApiKeyProperties.getInternalApiKey();

        // Fail closed: an unset/blank configured key must never match an empty or missing header.
        if (suppliedKey == null || configuredKey == null || configuredKey.isBlank()
                || !ConstantTimeSecrets.matches(suppliedKey, configuredKey)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getWriter(), ApiResponse.error("Unauthorized"));
            return;
        }

        filterChain.doFilter(request, response);
    }
}
