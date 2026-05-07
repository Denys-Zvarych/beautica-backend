package com.beautica.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
@EnableCaching
public class CacheConfig {

    /**
     * Per-cache TTL configuration using individual Caffeine caches.
     *
     * Cache inventory:
     *   service-categories  — catalog categories, rarely change — 60 min TTL, max 200 entries
     *   service-types       — service types per category — 60 min TTL, max 500 entries
     *   ownerSalons         — salon list per owner, high-frequency read — 5 min TTL, max 1000 entries
     *   masterServices      — service list per master, public endpoint — 10 min TTL, max 2000 entries
     */
    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.registerCustomCache("service-categories",
                Caffeine.newBuilder()
                        .maximumSize(200)
                        .expireAfterWrite(60, TimeUnit.MINUTES)
                        .build());
        manager.registerCustomCache("service-types",
                Caffeine.newBuilder()
                        .maximumSize(500)
                        .expireAfterWrite(60, TimeUnit.MINUTES)
                        .build());
        manager.registerCustomCache("ownerSalons",
                Caffeine.newBuilder()
                        .maximumSize(1000)
                        .expireAfterWrite(5, TimeUnit.MINUTES)
                        .build());
        manager.registerCustomCache("masterServices",
                Caffeine.newBuilder()
                        .maximumSize(2000)
                        .expireAfterWrite(10, TimeUnit.MINUTES)
                        .build());
        return manager;
    }
}
