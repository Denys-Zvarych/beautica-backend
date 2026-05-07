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

    @Value("${app.rate-limit.login-capacity:5}")
    private long loginCapacity;

    @Value("${app.rate-limit.refresh-capacity:20}")
    private long refreshCapacity;

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

    private Bandwidth bandwidthOf(long capacity, Duration period) {
        return BandwidthBuilder.builder()
                .capacity(capacity)
                .refillIntervally(capacity, period)
                .build();
    }
}
