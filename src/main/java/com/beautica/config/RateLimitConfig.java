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

    // Per-IP cap for POST /api/v1/auth/forgot-password and POST /api/v1/auth/reset-password
    // (60-minute window). Both paths share the same Caffeine bucket:
    //   • forgot-password is the email-bomb surface — keep it low.
    //   • reset-password uses the same family; a legitimate user needs at most a handful
    //     of attempts per hour (typo in new password, network error), so 3 is generous.
    // The 60-minute window matches the token TTL so a user who exhausts their budget
    // at the start of a reset session can always retry when the token would have expired
    // anyway. Configurable so integration tests can raise the cap.
    @Value("${app.rate-limit.forgot-password-capacity:3}")
    private long forgotPasswordCapacity;

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
     * Shared bucket for {@code POST /api/v1/auth/forgot-password} and
     * {@code POST /api/v1/auth/reset-password}.
     *
     * <p>Window is 60 minutes — matching the 1-hour token TTL. A user who exhausts
     * the budget at the start of a reset flow will be able to retry when the issued
     * token has expired, naturally forcing a fresh forgot-password request.
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

    private Bandwidth bandwidthOf(long capacity, Duration period) {
        return BandwidthBuilder.builder()
                .capacity(capacity)
                .refillIntervally(capacity, period)
                .build();
    }
}
