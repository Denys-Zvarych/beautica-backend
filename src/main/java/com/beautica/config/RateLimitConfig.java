package com.beautica.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BandwidthBuilder;
import io.github.bucket4j.Bucket;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class RateLimitConfig {

    @Value("${app.rate-limit.register-capacity:3}")
    private long registerCapacity;

    @Value("${app.rate-limit.login-capacity:5}")
    private long loginCapacity;

    @Value("${app.rate-limit.refresh-capacity:20}")
    private long refreshCapacity;

    @Value("${app.rate-limit.slots-capacity:60}")
    private long slotsCapacity;

    @Value("${app.rate-limit.device-token-capacity:30}")
    private long deviceTokenCapacity;

    // Per-IP cap for POST/DELETE /api/v1/media/* (60 s window). Phase 7.2 backlog
    // originally suggested 5/min; 10/min is the chosen ceiling because legitimate
    // clients may retry after a 400 (wrong MIME, oversize) or replace an avatar
    // immediately after upload — keeping headroom prevents false-positive lockouts
    // while still blocking sustained abuse.
    @Value("${app.rate-limit.media-upload-capacity:10}")
    private long mediaUploadCapacity;

    // Per-IP cap for POST /api/v1/auth/verify-email (15-minute window).
    // 10 attempts per window is generous for legitimate users while still
    // preventing brute-force of the 6-digit OTP space (1,000,000 combinations).
    // Configurable so integration tests running on 127.0.0.1 can raise the cap.
    @Value("${app.rate-limit.verify-email-capacity:10}")
    private long verifyEmailCapacity;

    private static final Duration VERIFY_EMAIL_WINDOW = Duration.ofMinutes(15);

    // Per-IP cap for POST /api/v1/auth/resend-verification (60-second window).
    // Configurable so integration tests running on 127.0.0.1 can raise the cap.
    @Value("${app.rate-limit.resend-verification-capacity:3}")
    private long resendVerificationCapacity;

    // Per-IP cap for POST /api/v1/auth/forgot-password (60-minute window).
    // forgot-password is the email-bomb surface — keep it low (3/hr). The 60-minute
    // window matches the token TTL so a user who exhausts their budget at the start of
    // a reset session can always retry when the token would have expired anyway.
    // Configurable so integration tests can raise the cap.
    @Value("${app.rate-limit.forgot-password-capacity:3}")
    private long forgotPasswordCapacity;

    // Per-IP cap for POST /api/v1/auth/reset-password (60-minute window).
    // Decoupled from forgot-password so a user behind NAT who spams forgot-password
    // does NOT block their own (or a co-located user's) reset-password submit. A
    // legitimate user may retry a new-password typo / network error several times
    // against one emailed token, so the cap is higher (10/hr) than the email-bomb
    // surface. reset-password sends no email, so a higher cap carries no spam risk.
    // Configurable so integration tests can raise the cap.
    @Value("${app.rate-limit.reset-password-capacity:10}")
    private long resetPasswordCapacity;

    // Per-IP cap for PATCH /api/v1/independent-masters/me/profile (60-second window).
    // 10/min prevents unbounded DB writes and cache churn from a token-holding client
    // while remaining generous enough for a legitimate profile-edit retry.
    @Value("${app.rate-limit.profile-update-capacity:10}")
    private long profileUpdateCapacity;

    // Per-IP cap for POST /api/v1/service-categories/requests (60-minute window).
    // This path sends an admin email on every successful request, so it is an
    // inbox-flood surface — kept low (5/hr) to match the forgot-password email-bomb
    // posture. IP-keyed for consistency with every other bucket in this filter
    // (JWT is not yet parsed when AuthRateLimitFilter runs). Configurable so
    // integration tests on 127.0.0.1 can raise the cap.
    @Value("${app.rate-limit.category-request-capacity:5}")
    private long categoryRequestCapacity;

    private static final Duration CATEGORY_REQUEST_WINDOW = Duration.ofMinutes(60);

    // Per-IP cap for POST /api/v1/service-types/suggest (60-minute window).
    // Like the category-request path, every successful suggestion emails the admin,
    // so this is an inbox-flood surface — kept low (5/hr) to match that posture.
    // IP-keyed for consistency with every other bucket in this filter (JWT is not yet
    // parsed when AuthRateLimitFilter runs). Configurable so integration tests on
    // 127.0.0.1 can raise the cap.
    @Value("${app.rate-limit.suggest-service-type-capacity:5}")
    private long suggestServiceTypeCapacity;

    private static final Duration SUGGEST_SERVICE_TYPE_WINDOW = Duration.ofMinutes(60);

    // Per-IP cap for the two first-time bulk-service-setup endpoints (60-second window):
    //   - POST /api/v1/independent-masters/me/services/bulk
    //   - POST /api/v1/salons/{salonId}/masters/{masterId}/services/bulk
    // Even the 409 (first-time-only) path runs full 100-item validation, so this is an
    // authenticated DoS-amplifier surface — kept low (10/min) to match the other write
    // buckets (media, profile-update, device-token). IP-keyed for consistency with every
    // other bucket in this filter (JWT is not yet parsed when AuthRateLimitFilter runs).
    // Configurable so integration tests on 127.0.0.1 can raise the cap.
    @Value("${app.rate-limit.bulk-service-setup-capacity:10}")
    private long bulkServiceSetupCapacity;

    // Per-IP cap for POST /api/v1/support/contact (60-minute window).
    // Each successful call sends an email to the support inbox, so this is an
    // email-bomb / outbound-quota DoS surface — kept low (5/hr) to mirror the
    // category-request and suggest-service-type inbox-flood posture. IP-keyed for
    // consistency with every other bucket in this filter (JWT is not yet parsed when
    // AuthRateLimitFilter runs). Configurable so integration tests on 127.0.0.1 can
    // raise the cap.
    @Value("${app.rate-limit.support-contact-capacity:5}")
    private long supportContactCapacity;

    private static final Duration SUPPORT_CONTACT_WINDOW = Duration.ofMinutes(60);

    // Per-IP cap for POST /api/v1/book/otp/send (15-minute window). Every successful
    // request dispatches an SMS via Turbosms — a billable, outbound-quota / SMS-bomb
    // surface — so this is the IP-layer defence that complements the per-phone rate
    // limit inside PhoneOtpService (dual-layer). Cap mirrors the per-phone service cap
    // (3 / 15 min) so a single IP cannot out-pace what one phone is allowed. IP-keyed
    // for consistency with every other bucket in this filter (JWT is not parsed when
    // AuthRateLimitFilter runs — and this is a permitAll endpoint anyway). Configurable
    // so integration tests on 127.0.0.1 can raise the cap.
    @Value("${app.rate-limit.otp-send-capacity:3}")
    private long otpSendCapacity;

    private static final Duration OTP_SEND_WINDOW = Duration.ofMinutes(15);

    @Bean
    public LoadingCache<String, Bucket> registerBuckets() {
        return Caffeine.newBuilder()
                .maximumSize(100_000)
                .expireAfterAccess(Duration.ofHours(1))
                .build(key -> Bucket.builder()
                        .addLimit(bandwidthOf(registerCapacity, Duration.ofMinutes(1)))
                        .build());
    }

    @Bean
    public LoadingCache<String, Bucket> loginBuckets() {
        return Caffeine.newBuilder()
                .maximumSize(100_000)
                .expireAfterAccess(Duration.ofHours(1))
                .build(key -> Bucket.builder()
                        .addLimit(bandwidthOf(loginCapacity, Duration.ofMinutes(1)))
                        .build());
    }

    @Bean
    public LoadingCache<String, Bucket> refreshBuckets() {
        return Caffeine.newBuilder()
                .maximumSize(100_000)
                .expireAfterAccess(Duration.ofHours(1))
                .build(key -> Bucket.builder()
                        .addLimit(bandwidthOf(refreshCapacity, Duration.ofMinutes(1)))
                        .build());
    }

    @Bean
    public LoadingCache<String, Bucket> slotsBuckets() {
        return Caffeine.newBuilder()
                .maximumSize(100_000)
                .expireAfterAccess(Duration.ofHours(1))
                .build(key -> Bucket.builder()
                        .addLimit(bandwidthOf(slotsCapacity, Duration.ofMinutes(1)))
                        .build());
    }

    @Bean
    public LoadingCache<String, Bucket> deviceTokenBuckets() {
        return Caffeine.newBuilder()
                .maximumSize(100_000)
                .expireAfterAccess(Duration.ofHours(1))
                .build(key -> Bucket.builder()
                        .addLimit(bandwidthOf(deviceTokenCapacity, Duration.ofMinutes(1)))
                        .build());
    }

    @Bean
    public LoadingCache<String, Bucket> mediaUploadBuckets() {
        return Caffeine.newBuilder()
                .maximumSize(100_000)
                .expireAfterAccess(Duration.ofHours(1))
                .build(key -> Bucket.builder()
                        .addLimit(bandwidthOf(mediaUploadCapacity, Duration.ofMinutes(1)))
                        .build());
    }

    @Bean
    public LoadingCache<String, Bucket> verifyEmailBuckets() {
        return Caffeine.newBuilder()
                .maximumSize(100_000)
                .expireAfterAccess(VERIFY_EMAIL_WINDOW.plusMinutes(5))
                .build(key -> Bucket.builder()
                        .addLimit(bandwidthOf(verifyEmailCapacity, VERIFY_EMAIL_WINDOW))
                        .build());
    }

    // Per-IP cap for POST /api/v1/auth/resend-verification (60-second window).
    // 3 requests per minute matches the per-account RESEND_COOLDOWN (60 s) and
    // is generous enough for a legitimate retry (network hiccup, paste error)
    // while blocking rapid volumetric abuse from a single IP.
    @Bean
    public LoadingCache<String, Bucket> resendVerificationBuckets() {
        return Caffeine.newBuilder()
                .maximumSize(100_000)
                .expireAfterAccess(90, java.util.concurrent.TimeUnit.SECONDS)
                .build(key -> Bucket.builder()
                        .addLimit(bandwidthOf(resendVerificationCapacity, Duration.ofSeconds(60)))
                        .build());
    }

    /**
     * Per-IP bucket for {@code POST /api/v1/auth/forgot-password}.
     *
     * <p>Window is 60 minutes — matching the 1-hour token TTL. A user who exhausts
     * the budget at the start of a reset flow will be able to retry when the issued
     * token has expired, naturally forcing a fresh forgot-password request.
     *
     * <p>Kept deliberately separate from {@link #resetPasswordBuckets()} so that
     * exhausting the email-bomb surface here cannot deplete the reset-confirm budget
     * for a user sharing the same NAT egress IP.
     *
     * <p>{@code expireAfterAccess(65 min)} gives a 5-minute grace so the bucket entry
     * is not evicted the moment the window rolls over (avoids a false-start on the
     * very next request).
     */
    @Bean
    public LoadingCache<String, Bucket> forgotPasswordBuckets() {
        return Caffeine.newBuilder()
                .maximumSize(100_000)
                .expireAfterAccess(Duration.ofMinutes(65))
                .build(key -> Bucket.builder()
                        .addLimit(bandwidthOf(forgotPasswordCapacity, Duration.ofMinutes(60)))
                        .build());
    }

    /**
     * Per-IP bucket for {@code PATCH /api/v1/independent-masters/me/profile}.
     *
     * <p>Cap: 10 requests per 60-second window per source IP. An authenticated
     * user with a valid JWT can send at most 10 profile-update writes per minute;
     * beyond that the bucket returns 429. This prevents sustained cache churn
     * ({@code master-detail-by-user} / {@code master-by-user} evictions) and
     * unbounded DB dirty-checking flushes without impacting legitimate usage.
     *
     * <p>IP-keyed (not user-keyed) for consistency with existing buckets: JWT
     * parsing happens in {@link com.beautica.auth.JwtAuthenticationFilter} which
     * runs after this filter, so the principal is not yet available here.
     */
    @Bean
    public LoadingCache<String, Bucket> profileUpdateBuckets() {
        return Caffeine.newBuilder()
                .maximumSize(100_000)
                .expireAfterAccess(Duration.ofHours(1))
                .build(key -> Bucket.builder()
                        .addLimit(bandwidthOf(profileUpdateCapacity, Duration.ofMinutes(1)))
                        .build());
    }

    /**
     * Per-IP bucket for {@code POST /api/v1/auth/reset-password}.
     *
     * <p>Decoupled from {@link #forgotPasswordBuckets()} (SEC fix): coupling the two
     * paths to one bucket meant a NAT-shared client spamming forgot-password could
     * lock out their own reset-password submit. reset-password sends no email, so a
     * higher cap (10/hr) carries no spam risk and tolerates legitimate new-password
     * typo retries against a single emailed token.
     *
     * <p>Same 60-minute window + 5-minute eviction grace as forgot-password.
     */
    @Bean
    public LoadingCache<String, Bucket> resetPasswordBuckets() {
        return Caffeine.newBuilder()
                .maximumSize(100_000)
                .expireAfterAccess(Duration.ofMinutes(65))
                .build(key -> Bucket.builder()
                        .addLimit(bandwidthOf(resetPasswordCapacity, Duration.ofMinutes(60)))
                        .build());
    }

    /**
     * Per-IP bucket for {@code POST /api/v1/service-categories/requests}.
     *
     * <p>Cap: 5 requests per 60-minute window per source IP. Every successful
     * request triggers an admin notification email, so this is an inbox-flood
     * surface; the low cap mirrors the forgot-password email-bomb posture.
     * {@code expireAfterAccess(65 min)} gives a 5-minute grace past the window so
     * the entry is not evicted the instant the window rolls over.
     */
    @Bean
    public LoadingCache<String, Bucket> categoryRequestBuckets() {
        return Caffeine.newBuilder()
                .maximumSize(100_000)
                .expireAfterAccess(CATEGORY_REQUEST_WINDOW.plusMinutes(5))
                .build(key -> Bucket.builder()
                        .addLimit(bandwidthOf(categoryRequestCapacity, CATEGORY_REQUEST_WINDOW))
                        .build());
    }

    /**
     * Per-IP bucket for {@code POST /api/v1/service-types/suggest}.
     *
     * <p>Cap: 5 requests per 60-minute window per source IP. Every successful
     * suggestion triggers an admin notification email, so this is an inbox-flood
     * surface; the low cap mirrors the {@link #categoryRequestBuckets()} posture.
     * {@code expireAfterAccess(65 min)} gives a 5-minute grace past the window so
     * the entry is not evicted the instant the window rolls over.
     */
    @Bean
    public LoadingCache<String, Bucket> suggestServiceTypeBuckets() {
        return Caffeine.newBuilder()
                .maximumSize(100_000)
                .expireAfterAccess(SUGGEST_SERVICE_TYPE_WINDOW.plusMinutes(5))
                .build(key -> Bucket.builder()
                        .addLimit(bandwidthOf(suggestServiceTypeCapacity, SUGGEST_SERVICE_TYPE_WINDOW))
                        .build());
    }

    /**
     * Per-IP bucket for the two first-time bulk-service-setup endpoints
     * ({@code POST .../services/bulk}).
     *
     * <p>Cap: 10 requests per 60-second window per source IP — matching the other
     * authenticated write buckets ({@link #mediaUploadBuckets()},
     * {@link #profileUpdateBuckets()}, {@link #deviceTokenBuckets()}). Each request can
     * carry up to 100 items whose validation (type resolution, category checks,
     * persistence) runs even on the 409 first-time-only path, so an unthrottled
     * token-holder is a DoS amplifier; the low cap removes that lever while staying
     * generous for a legitimate retry.
     *
     * <p>IP-keyed (not user-keyed) for consistency with every other bucket in this
     * filter: JWT parsing happens in
     * {@link com.beautica.auth.JwtAuthenticationFilter} which runs after this filter.
     */
    @Bean
    public LoadingCache<String, Bucket> bulkServiceSetupBuckets() {
        return Caffeine.newBuilder()
                .maximumSize(100_000)
                .expireAfterAccess(Duration.ofHours(1))
                .build(key -> Bucket.builder()
                        .addLimit(bandwidthOf(bulkServiceSetupCapacity, Duration.ofMinutes(1)))
                        .build());
    }

    /**
     * Per-IP bucket for {@code POST /api/v1/support/contact}.
     *
     * <p>Cap: 5 requests per 60-minute window per source IP. Every successful request
     * sends an email to the fixed support inbox, so this is an email-bomb / outbound-quota
     * surface; the low cap mirrors the {@link #categoryRequestBuckets()} and
     * {@link #suggestServiceTypeBuckets()} posture. {@code expireAfterAccess(65 min)} gives
     * a 5-minute grace past the window so the entry is not evicted the instant the window
     * rolls over.
     */
    @Bean
    public LoadingCache<String, Bucket> supportContactBuckets() {
        return Caffeine.newBuilder()
                .maximumSize(100_000)
                .expireAfterAccess(SUPPORT_CONTACT_WINDOW.plusMinutes(5))
                .build(key -> Bucket.builder()
                        .addLimit(bandwidthOf(supportContactCapacity, SUPPORT_CONTACT_WINDOW))
                        .build());
    }

    /**
     * Per-IP bucket for {@code POST /api/v1/book/otp/send}.
     *
     * <p>Cap: 3 requests per 15-minute window per source IP — matching the per-phone
     * service-layer cap in {@code PhoneOtpService} so neither layer is the looser of the
     * two. Each successful request sends a billable SMS, so this is an SMS-bomb /
     * outbound-quota surface guarded at the IP layer (the per-phone check guards a single
     * number; this guards a single network identity rotating phones).
     * {@code expireAfterAccess(20 min)} gives a 5-minute grace past the window so the
     * entry is not evicted the instant the window rolls over.
     */
    @Bean
    public LoadingCache<String, Bucket> otpSendBuckets() {
        return Caffeine.newBuilder()
                .maximumSize(100_000)
                .expireAfterAccess(OTP_SEND_WINDOW.plusMinutes(5))
                .build(key -> Bucket.builder()
                        .addLimit(bandwidthOf(otpSendCapacity, OTP_SEND_WINDOW))
                        .build());
    }

    private Bandwidth bandwidthOf(long capacity, Duration period) {
        return BandwidthBuilder.builder()
                .capacity(capacity)
                .refillIntervally(capacity, period)
                .build();
    }
}
