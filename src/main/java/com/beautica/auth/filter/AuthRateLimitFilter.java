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
import java.nio.charset.StandardCharsets;

@Component
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final byte[] TOO_MANY_REQUESTS_BODY =
            "{\"error\":\"Too many requests\"}".getBytes(StandardCharsets.UTF_8);

    private static final String REGISTER_PATH = "/api/v1/auth/register";
    private static final String REGISTER_IM_PATH = "/api/v1/auth/register/independent-master";
    private static final String LOGIN_PATH = "/api/v1/auth/login";
    private static final String REFRESH_PATH = "/api/v1/auth/refresh";
    private static final String VERIFY_EMAIL_PATH = "/api/v1/auth/verify-email";
    private static final String RESEND_VERIFICATION_PATH = "/api/v1/auth/resend-verification";
    private static final String SLOTS_PATH_PREFIX = "/api/v1/masters/";
    private static final String SLOTS_PATH_SUFFIX = "/slots";
    private static final String DEVICE_TOKEN_PATH = "/api/v1/devices/token";
    private static final String MEDIA_PATH_PREFIX = "/api/v1/media/";
    private static final int RETRY_AFTER_SECONDS = 60;
    // verify-email bucket window is 15 minutes — Retry-After must reflect the actual window
    // so clients do not spin-retry every 60 s and waste their remaining IP quota.
    private static final int VERIFY_EMAIL_RETRY_AFTER_SECONDS = 900;

    private final LoadingCache<String, Bucket> registerBuckets;
    private final LoadingCache<String, Bucket> loginBuckets;
    private final LoadingCache<String, Bucket> refreshBuckets;
    private final LoadingCache<String, Bucket> verifyEmailBuckets;
    private final LoadingCache<String, Bucket> slotsBuckets;
    // IP-keyed (not user-keyed): JWT parsing is the responsibility of JwtAuthenticationFilter
    // which runs *after* this filter; resolving the principal here would duplicate that work
    // and couple the rate limiter to the auth subsystem.
    private final LoadingCache<String, Bucket> deviceTokenBuckets;
    // Same IP-keyed rationale applies to media uploads — JwtAuthenticationFilter
    // runs after this one, so the rate limiter sees only the network identity.
    private final LoadingCache<String, Bucket> mediaUploadBuckets;
    private final LoadingCache<String, Bucket> resendVerificationBuckets;

    public AuthRateLimitFilter(
            @Qualifier("registerBuckets") LoadingCache<String, Bucket> registerBuckets,
            @Qualifier("loginBuckets") LoadingCache<String, Bucket> loginBuckets,
            @Qualifier("refreshBuckets") LoadingCache<String, Bucket> refreshBuckets,
            @Qualifier("verifyEmailBuckets") LoadingCache<String, Bucket> verifyEmailBuckets,
            @Qualifier("slotsBuckets") LoadingCache<String, Bucket> slotsBuckets,
            @Qualifier("deviceTokenBuckets") LoadingCache<String, Bucket> deviceTokenBuckets,
            @Qualifier("mediaUploadBuckets") LoadingCache<String, Bucket> mediaUploadBuckets,
            @Qualifier("resendVerificationBuckets") LoadingCache<String, Bucket> resendVerificationBuckets) {
        this.registerBuckets = registerBuckets;
        this.loginBuckets = loginBuckets;
        this.refreshBuckets = refreshBuckets;
        this.verifyEmailBuckets = verifyEmailBuckets;
        this.slotsBuckets = slotsBuckets;
        this.deviceTokenBuckets = deviceTokenBuckets;
        this.mediaUploadBuckets = mediaUploadBuckets;
        this.resendVerificationBuckets = resendVerificationBuckets;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        String method = request.getMethod();

        // Device-token rate-limit: POST or DELETE /api/v1/devices/token — checked before
        // the POST-only branch so DELETE is also covered.
        if (DEVICE_TOKEN_PATH.equals(path)
                && (HttpMethod.POST.matches(method) || HttpMethod.DELETE.matches(method))) {
            applyRateLimit(request, response, filterChain, deviceTokenBuckets, RETRY_AFTER_SECONDS);
            return;
        }

        // Media rate-limit: POST or DELETE /api/v1/media/* (avatar + portfolio).
        // Checked before the POST-only branch so DELETE /api/v1/media/avatar and
        // DELETE /api/v1/media/portfolio/{id} are also covered. Public GET
        // listings (/api/v1/salons/{id}/portfolio etc.) are intentionally NOT
        // rate-limited here — they're read-only and cached behind R2/CDN.
        if ((HttpMethod.POST.matches(method) || HttpMethod.DELETE.matches(method))
                && path.startsWith(MEDIA_PATH_PREFIX)) {
            applyRateLimit(request, response, filterChain, mediaUploadBuckets, RETRY_AFTER_SECONDS);
            return;
        }

        // Slots rate-limit: GET /api/v1/masters/{masterId}/slots — checked before POST guard
        if (HttpMethod.GET.matches(method)
                && path.startsWith(SLOTS_PATH_PREFIX)
                && path.endsWith(SLOTS_PATH_SUFFIX)) {
            applyRateLimit(request, response, filterChain, slotsBuckets, RETRY_AFTER_SECONDS);
            return;
        }

        if (!HttpMethod.POST.matches(method)) {
            filterChain.doFilter(request, response);
            return;
        }

        LoadingCache<String, Bucket> cache;

        int retryAfterSeconds = RETRY_AFTER_SECONDS;

        if (REGISTER_PATH.equals(path) || REGISTER_IM_PATH.equals(path)) {
            cache = registerBuckets;
        } else if (LOGIN_PATH.equals(path)) {
            cache = loginBuckets;
        } else if (REFRESH_PATH.equals(path)) {
            cache = refreshBuckets;
        } else if (VERIFY_EMAIL_PATH.equals(path)) {
            cache = verifyEmailBuckets;
            retryAfterSeconds = VERIFY_EMAIL_RETRY_AFTER_SECONDS;
        } else if (RESEND_VERIFICATION_PATH.equals(path)) {
            cache = resendVerificationBuckets;
        } else {
            filterChain.doFilter(request, response);
            return;
        }

        applyRateLimit(request, response, filterChain, cache, retryAfterSeconds);
    }

    private void applyRateLimit(HttpServletRequest request,
                                HttpServletResponse response,
                                FilterChain filterChain,
                                LoadingCache<String, Bucket> cache,
                                int retryAfterSeconds) throws ServletException, IOException {
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
            response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
            response.setContentLength(TOO_MANY_REQUESTS_BODY.length);
            response.getOutputStream().write(TOO_MANY_REQUESTS_BODY);
        }
    }

    private String resolveClientIp(HttpServletRequest request) {
        String xfwd = request.getHeader("X-Forwarded-For");
        if (xfwd != null && !xfwd.isBlank()) {
            String[] parts = xfwd.split(",");
            // Rightmost entry is appended by Railway's trusted proxy — cannot be spoofed.
            for (int i = parts.length - 1; i >= 0; i--) {
                String part = parts[i].trim();
                if (!part.isEmpty()) {
                    return part.length() > 45 ? request.getRemoteAddr() : part;
                }
            }
        }
        return request.getRemoteAddr();
    }
}
