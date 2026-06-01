package com.beautica.config;

import com.beautica.common.ApiResponse;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Servlet filter that guards all {@code /api/v1/internal/**} endpoints with a
 * static API key carried in the {@code X-Internal-Key} request header.
 *
 * <h2>Authentication mechanism</h2>
 * The filter compares the supplied header value against
 * {@link InternalApiKeyProperties#getInternalApiKey()} using
 * {@link MessageDigest#isEqual(byte[], byte[])} — a constant-time comparison
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

        if (suppliedKey == null || !constantTimeEquals(suppliedKey,
                internalApiKeyProperties.getInternalApiKey())) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getWriter(), ApiResponse.error("Unauthorized"));
            return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Constant-time comparison to prevent timing-oracle attacks.
     *
     * <p>Both strings are hashed to equal-length byte arrays with SHA-256 before
     * comparison so that {@link MessageDigest#isEqual} cannot leak length
     * information via short-circuit returns.
     */
    private static boolean constantTimeEquals(String a, String b) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashA = digest.digest(a.getBytes(StandardCharsets.UTF_8));
            digest.reset();
            byte[] hashB = digest.digest(b.getBytes(StandardCharsets.UTF_8));
            return MessageDigest.isEqual(hashA, hashB);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed present by the JVM spec; this branch is unreachable.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
