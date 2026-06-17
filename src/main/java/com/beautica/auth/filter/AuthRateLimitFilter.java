package com.beautica.auth.filter;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BandwidthBuilder;
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
import java.time.Duration;

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
    private static final String FORGOT_PASSWORD_PATH = "/api/v1/auth/forgot-password";
    private static final String RESET_PASSWORD_PATH = "/api/v1/auth/reset-password";
    private static final String SLOTS_PATH_PREFIX = "/api/v1/masters/";
    private static final String SLOTS_PATH_SUFFIX = "/slots";
    private static final String DEVICE_TOKEN_PATH = "/api/v1/devices/token";
    private static final String MEDIA_PATH_PREFIX = "/api/v1/media/";
    private static final String PROFILE_UPDATE_PATH = "/api/v1/independent-masters/me/profile";
    private static final String USER_ME_PATH = "/api/v1/users/me";
    private static final String IM_LOCALITY_PATH = "/api/v1/independent-masters/me";
    private static final String MASTERS_ME_PROFILE_PATH = "/api/v1/masters/me/profile";
    private static final String CATEGORY_REQUEST_PATH = "/api/v1/service-categories/requests";
    private static final String SUGGEST_SERVICE_TYPE_PATH = "/api/v1/service-types/suggest";
    // First-time bulk-service-setup endpoints. The independent path is an exact match;
    // the salon path carries {salonId}/{masterId} variables, so it is matched by prefix +
    // suffix (same technique as the parameterized SLOTS_PATH below).
    private static final String BULK_IM_SERVICES_PATH = "/api/v1/independent-masters/me/services/bulk";
    private static final String BULK_SALON_SERVICES_PREFIX = "/api/v1/salons/";
    private static final String BULK_SALON_SERVICES_SUFFIX = "/services/bulk";
    private static final String SUPPORT_CONTACT_PATH = "/api/v1/support/contact";
    private static final String OTP_SEND_PATH = "/api/v1/book/otp/send";
    private static final String OTP_VERIFY_PATH = "/api/v1/book/otp/verify";
    private static final int RETRY_AFTER_SECONDS = 60;
    // category-request bucket window is 60 minutes — Retry-After reflects the window.
    private static final int CATEGORY_REQUEST_RETRY_AFTER_SECONDS = 3600;
    // suggest-service-type bucket window is 60 minutes — Retry-After reflects the window.
    private static final int SUGGEST_SERVICE_TYPE_RETRY_AFTER_SECONDS = 3600;
    // support-contact bucket window is 60 minutes — Retry-After reflects the window.
    private static final int SUPPORT_CONTACT_RETRY_AFTER_SECONDS = 3600;
    // verify-email bucket window is 15 minutes — Retry-After must reflect the actual window
    // so clients do not spin-retry every 60 s and waste their remaining IP quota.
    private static final int VERIFY_EMAIL_RETRY_AFTER_SECONDS = 900;
    // forgot-password / reset-password bucket window is 60 minutes.
    private static final int FORGOT_PASSWORD_RETRY_AFTER_SECONDS = 3600;
    // otp-send bucket window is 15 minutes — Retry-After reflects the actual window.
    private static final int OTP_SEND_RETRY_AFTER_SECONDS = 900;
    // otp-verify bucket window is 15 minutes — Retry-After reflects the actual window.
    private static final int OTP_VERIFY_RETRY_AFTER_SECONDS = 900;
    // Per-IP cap for POST /api/v1/book/otp/verify (10 / 15 min). Higher than /send (3)
    // since a legitimate guest may retype a code, but bounded so a single IP cannot pour
    // unlimited verify attempts at freshly-sent OTPs and defeat the per-OTP attempt cap by
    // re-sending. This bucket is built internally (not an injected @Qualifier bean) so the
    // existing 16-arg constructor — depended on by several slice/regression tests — is
    // unchanged.
    private static final long OTP_VERIFY_CAPACITY = 10;
    private static final Duration OTP_VERIFY_WINDOW = Duration.ofMinutes(15);

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
    // Per-IP bucket for PATCH /api/v1/independent-masters/me/profile — prevents
    // unbounded DB writes and cache churn from a token-holding client (10/min).
    private final LoadingCache<String, Bucket> profileUpdateBuckets;
    private final LoadingCache<String, Bucket> resendVerificationBuckets;
    // Separate per-IP buckets for forgot-password and reset-password (each 60-minute
    // window). Decoupled (SEC fix): a NAT-shared client spamming forgot-password must
    // not deplete the reset-confirm budget. forgot-password is the email-bomb surface
    // (low cap); reset-password sends no email (higher cap, tolerant of typo retries).
    private final LoadingCache<String, Bucket> forgotPasswordBuckets;
    private final LoadingCache<String, Bucket> resetPasswordBuckets;
    // Per-IP bucket for POST /api/v1/service-categories/requests — every successful
    // request emails the admin, so this is an inbox-flood surface (5/hr).
    private final LoadingCache<String, Bucket> categoryRequestBuckets;
    // Per-IP bucket for POST /api/v1/service-types/suggest — every successful
    // suggestion emails the admin, so this is an inbox-flood surface (5/hr).
    private final LoadingCache<String, Bucket> suggestServiceTypeBuckets;
    // Per-IP bucket for the two first-time bulk-service-setup endpoints. Even the 409
    // first-time-only path runs full 100-item validation, so an authenticated token-holder
    // is a DoS amplifier without this guard (10/min).
    private final LoadingCache<String, Bucket> bulkServiceSetupBuckets;
    // Per-IP bucket for POST /api/v1/support/contact — every successful request emails
    // the support inbox, so this is an email-bomb / outbound-quota surface (5/hr).
    private final LoadingCache<String, Bucket> supportContactBuckets;
    // Per-IP bucket for POST /api/v1/book/otp/send — every successful request sends a
    // billable SMS, so this is an SMS-bomb / outbound-quota surface (3 / 15 min). This is
    // the IP-layer defence complementing the per-phone rate limit in PhoneOtpService.
    private final LoadingCache<String, Bucket> otpSendBuckets;
    // Per-IP bucket for POST /api/v1/book/otp/verify — the IP-layer half of the CRITICAL
    // brute-force fix (the per-OTP attempt counter in PhoneOtpService is the other half).
    // Built internally rather than injected so the public 16-arg constructor stays stable
    // for the slice/regression tests that construct this filter directly.
    private final LoadingCache<String, Bucket> otpVerifyBuckets;

    public AuthRateLimitFilter(
            @Qualifier("registerBuckets") LoadingCache<String, Bucket> registerBuckets,
            @Qualifier("loginBuckets") LoadingCache<String, Bucket> loginBuckets,
            @Qualifier("refreshBuckets") LoadingCache<String, Bucket> refreshBuckets,
            @Qualifier("verifyEmailBuckets") LoadingCache<String, Bucket> verifyEmailBuckets,
            @Qualifier("slotsBuckets") LoadingCache<String, Bucket> slotsBuckets,
            @Qualifier("deviceTokenBuckets") LoadingCache<String, Bucket> deviceTokenBuckets,
            @Qualifier("mediaUploadBuckets") LoadingCache<String, Bucket> mediaUploadBuckets,
            @Qualifier("profileUpdateBuckets") LoadingCache<String, Bucket> profileUpdateBuckets,
            @Qualifier("resendVerificationBuckets") LoadingCache<String, Bucket> resendVerificationBuckets,
            @Qualifier("forgotPasswordBuckets") LoadingCache<String, Bucket> forgotPasswordBuckets,
            @Qualifier("resetPasswordBuckets") LoadingCache<String, Bucket> resetPasswordBuckets,
            @Qualifier("categoryRequestBuckets") LoadingCache<String, Bucket> categoryRequestBuckets,
            @Qualifier("suggestServiceTypeBuckets") LoadingCache<String, Bucket> suggestServiceTypeBuckets,
            @Qualifier("bulkServiceSetupBuckets") LoadingCache<String, Bucket> bulkServiceSetupBuckets,
            @Qualifier("supportContactBuckets") LoadingCache<String, Bucket> supportContactBuckets,
            @Qualifier("otpSendBuckets") LoadingCache<String, Bucket> otpSendBuckets) {
        this.registerBuckets = registerBuckets;
        this.loginBuckets = loginBuckets;
        this.refreshBuckets = refreshBuckets;
        this.verifyEmailBuckets = verifyEmailBuckets;
        this.slotsBuckets = slotsBuckets;
        this.deviceTokenBuckets = deviceTokenBuckets;
        this.mediaUploadBuckets = mediaUploadBuckets;
        this.profileUpdateBuckets = profileUpdateBuckets;
        this.resendVerificationBuckets = resendVerificationBuckets;
        this.forgotPasswordBuckets = forgotPasswordBuckets;
        this.resetPasswordBuckets = resetPasswordBuckets;
        this.categoryRequestBuckets = categoryRequestBuckets;
        this.suggestServiceTypeBuckets = suggestServiceTypeBuckets;
        this.bulkServiceSetupBuckets = bulkServiceSetupBuckets;
        this.supportContactBuckets = supportContactBuckets;
        this.otpSendBuckets = otpSendBuckets;
        this.otpVerifyBuckets = Caffeine.newBuilder()
                .maximumSize(100_000)
                .expireAfterAccess(OTP_VERIFY_WINDOW.plusMinutes(5))
                .build(key -> Bucket.builder()
                        .addLimit(otpVerifyBandwidth())
                        .build());
    }

    private static Bandwidth otpVerifyBandwidth() {
        return BandwidthBuilder.builder()
                .capacity(OTP_VERIFY_CAPACITY)
                .refillIntervally(OTP_VERIFY_CAPACITY, OTP_VERIFY_WINDOW)
                .build();
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

        // Profile-update rate-limit: PATCH /me endpoints — checked before the POST-only
        // guard so PATCH is covered. Cap: 10 requests/min per IP (shared profileUpdateBuckets).
        // JWT is not yet parsed at this point; rate limiting is IP-keyed for consistency
        // with all other buckets in this filter (see comment on deviceTokenBuckets field).
        // Covers three paths:
        //   - /api/v1/independent-masters/me/profile (bio, phone, instagram)
        //   - /api/v1/users/me                       (first/last name, phone, locality — all roles)
        //   - /api/v1/independent-masters/me          (locality-only — INDEPENDENT_MASTER)
        if (HttpMethod.PATCH.matches(method)
                && (PROFILE_UPDATE_PATH.equals(path)
                        || USER_ME_PATH.equals(path)
                        || IM_LOCALITY_PATH.equals(path)
                        || MASTERS_ME_PROFILE_PATH.equals(path))) {
            applyRateLimit(request, response, filterChain, profileUpdateBuckets, RETRY_AFTER_SECONDS);
            return;
        }

        // Bulk-service-setup rate-limit: POST on either bulk route. The independent route
        // is an exact path; the salon route carries {salonId}/{masterId} variables, so it is
        // matched by prefix + suffix (same technique as the SLOTS_PATH branch above). Cap:
        // 10 requests/min per IP (shared bulkServiceSetupBuckets).
        if (HttpMethod.POST.matches(method)
                && (BULK_IM_SERVICES_PATH.equals(path)
                        || (path.startsWith(BULK_SALON_SERVICES_PREFIX)
                                && path.endsWith(BULK_SALON_SERVICES_SUFFIX)))) {
            applyRateLimit(request, response, filterChain, bulkServiceSetupBuckets, RETRY_AFTER_SECONDS);
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
        } else if (FORGOT_PASSWORD_PATH.equals(path)) {
            cache = forgotPasswordBuckets;
            retryAfterSeconds = FORGOT_PASSWORD_RETRY_AFTER_SECONDS;
        } else if (RESET_PASSWORD_PATH.equals(path)) {
            cache = resetPasswordBuckets;
            retryAfterSeconds = FORGOT_PASSWORD_RETRY_AFTER_SECONDS;
        } else if (CATEGORY_REQUEST_PATH.equals(path)) {
            cache = categoryRequestBuckets;
            retryAfterSeconds = CATEGORY_REQUEST_RETRY_AFTER_SECONDS;
        } else if (SUGGEST_SERVICE_TYPE_PATH.equals(path)) {
            cache = suggestServiceTypeBuckets;
            retryAfterSeconds = SUGGEST_SERVICE_TYPE_RETRY_AFTER_SECONDS;
        } else if (SUPPORT_CONTACT_PATH.equals(path)) {
            cache = supportContactBuckets;
            retryAfterSeconds = SUPPORT_CONTACT_RETRY_AFTER_SECONDS;
        } else if (OTP_SEND_PATH.equals(path)) {
            cache = otpSendBuckets;
            retryAfterSeconds = OTP_SEND_RETRY_AFTER_SECONDS;
        } else if (OTP_VERIFY_PATH.equals(path)) {
            cache = otpVerifyBuckets;
            retryAfterSeconds = OTP_VERIFY_RETRY_AFTER_SECONDS;
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
            response.setHeader("X-Content-Type-Options", "nosniff");
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
