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
     *   service-type-by-id  — single service type by ID — 60 min TTL, max 500 entries
     *   ownerSalons         — salon list per owner, high-frequency read — 5 min TTL, max 1000 entries
     *   masterServices      — service list per master, public endpoint — 10 min TTL, max 500 entries
     *   available-slots     — slot availability per master/date/service — 60 sec TTL, max 500 entries
     *   master-calendar     — paginated booking calendar per master/date range — 30 sec TTL, max 500 entries
     *   service-type-search — trigram search results per (q, categoryId) — 5 min TTL, max 1000 entries
     *   salon-detail        — single salon entity by ID — 5 min TTL, max 1000 entries
     *   search:masters      — discovery results, first 5 pages only — 60 sec TTL, max 500 entries
     *   search:salons       — discovery results, first 5 pages only — 60 sec TTL, max 500 entries
     *   portfolio           — per-entity portfolio listing, public unauthenticated GET — 5 min TTL, max 2000 entries
     *
     * <p>Note on {@code search:*}: short TTL is preferred over explicit
     * {@code @CacheEvict} on master/salon write paths because discovery results
     * aggregate across many entities — the eviction key set would balloon
     * (a single rating update touches one master but invalidates every cached
     * query that returned it). 60-second TTL trades freshness for hit-rate on
     * the hot-path first 5 pages and avoids tying every write to a fan-out
     * eviction.
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
        manager.registerCustomCache("service-type-by-id",
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
                        .maximumSize(500)
                        .expireAfterWrite(10, TimeUnit.MINUTES)
                        .build());
        manager.registerCustomCache("available-slots",
                Caffeine.newBuilder()
                        .maximumSize(500)
                        .expireAfterWrite(60, TimeUnit.SECONDS)
                        .build());
        manager.registerCustomCache("master-calendar",
                Caffeine.newBuilder()
                        .maximumSize(500)
                        .expireAfterWrite(30, TimeUnit.SECONDS)
                        .build());
        manager.registerCustomCache("service-type-search",
                Caffeine.newBuilder()
                        .maximumSize(1000)
                        .expireAfterWrite(5, TimeUnit.MINUTES)
                        .build());
        manager.registerCustomCache("salon-detail",
                Caffeine.newBuilder()
                        .maximumSize(1000)
                        .expireAfterWrite(5, TimeUnit.MINUTES)
                        .build());
        manager.registerCustomCache("search:masters",
                Caffeine.newBuilder()
                        .maximumSize(500)
                        .expireAfterWrite(60, TimeUnit.SECONDS)
                        .build());
        manager.registerCustomCache("search:salons",
                Caffeine.newBuilder()
                        .maximumSize(500)
                        .expireAfterWrite(60, TimeUnit.SECONDS)
                        .build());
        // Phase 7.7 — public portfolio listing is read-mostly; the 5-min TTL bounds stale
        // exposure if an eviction is missed, and 2000 entries cover the most active
        // salons + masters for current scale.
        manager.registerCustomCache("portfolio",
                Caffeine.newBuilder()
                        .maximumSize(2000)
                        .expireAfterWrite(5, TimeUnit.MINUTES)
                        .build());
        return manager;
    }
}
