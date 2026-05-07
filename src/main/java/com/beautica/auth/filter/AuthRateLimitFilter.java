package com.beautica.auth.filter;

import com.github.benmanes.caffeine.cache.LoadingCache;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final String LOGIN_PATH = "/api/v1/auth/login";
    private static final String REFRESH_PATH = "/api/v1/auth/refresh";
    private static final int RETRY_AFTER_SECONDS = 60;

    private final LoadingCache<String, Bucket> loginBuckets;
    private final LoadingCache<String, Bucket> refreshBuckets;

    public AuthRateLimitFilter(
            @Qualifier("loginBuckets") LoadingCache<String, Bucket> loginBuckets,
            @Qualifier("refreshBuckets") LoadingCache<String, Bucket> refreshBuckets) {
        this.loginBuckets = loginBuckets;
        this.refreshBuckets = refreshBuckets;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!HttpMethod.POST.matches(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();
        LoadingCache<String, Bucket> cache;

        if (LOGIN_PATH.equals(path)) {
            cache = loginBuckets;
        } else if (REFRESH_PATH.equals(path)) {
            cache = refreshBuckets;
        } else {
            filterChain.doFilter(request, response);
            return;
        }

        String ip = resolveClientIp(request);
        // Clamp to max IPv6 length (45 chars) to prevent oversized Caffeine cache keys
        // crafted via a long X-Forwarded-For header value.
        if (ip.length() > 45) {
            ip = request.getRemoteAddr();
        }
        Bucket bucket = cache.get(ip);

        if (bucket.tryConsume(1)) {
            filterChain.doFilter(request, response);
        } else {
            response.setStatus(429);
            response.setContentType("application/json");
            response.setHeader("Retry-After", String.valueOf(RETRY_AFTER_SECONDS));
            response.getWriter().write("{\"error\":\"Too many requests\"}");
        }
    }

    private String resolveClientIp(HttpServletRequest request) {
        String xfwd = request.getHeader("X-Forwarded-For");
        if (xfwd != null && !xfwd.isBlank()) {
            String[] parts = xfwd.split(",");
            for (int i = 0; i < parts.length; i++) {
                String part = parts[i].trim();
                if (!part.isEmpty()) {
                    String ip = part;
                    // clamp to max IPv6 length (45 chars) to prevent oversized cache keys
                    return ip.length() > 45 ? request.getRemoteAddr() : ip;
                }
            }
        }
        return request.getRemoteAddr();
    }
}
