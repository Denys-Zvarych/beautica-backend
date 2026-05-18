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
    private static final long VERIFY_EMAIL_CAPACITY = 10;
    private static final Duration VERIFY_EMAIL_WINDOW = Duration.ofMinutes(15);

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
                        .addLimit(bandwidthOf(VERIFY_EMAIL_CAPACITY, VERIFY_EMAIL_WINDOW))
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
                        .addLimit(bandwidthOf(3, Duration.ofSeconds(60)))
                        .build());
    }

    private Bandwidth bandwidthOf(long capacity, Duration period) {
        return BandwidthBuilder.builder()
                .capacity(capacity)
                .refillIntervally(capacity, period)
                .build();
    }
}
