package com.beautica.booking.service;

import com.beautica.booking.dto.BookingLinkKind;
import com.beautica.booking.repository.RetiredBookingSlugRepository;
import com.beautica.common.exception.GoneException;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.stereotype.Component;

/**
 * Phase 379 D2 — the ONE "is this missed public slug a retired link?" check, shared by every public
 * slug-keyed route ({@link BookingSlugService}, {@link SalonBookingSlugService}, {@link GuestBookingService}).
 *
 * <p>Called only on the MISS path, after the live resolver found nothing: a valid link costs zero extra
 * queries. The probe is a primary-key lookup on {@code (kind, slug_hash)} — the slug is hashed through
 * {@link RetiredSlugHasher} (V205: the table holds no plaintext). The kind is part of the key, so a MASTER
 * tombstone never makes the salon route of an equal slug 410, and vice versa (378 D3).
 *
 * <p><b>Known-retired cache (379 audit, perf F1/F2).</b> A confirmed tombstone is remembered in
 * {@link #KNOWN_RETIRED_CACHE} (registered in {@code CacheConfig}), so repeated hits on a dead link cost no
 * query. Tombstones are append-only and never re-issued, so a positive never goes stale. Negatives are NEVER
 * cached, because a 404 slug may later be issued.
 *
 * <p><b>No loader (379 audit cycle 2, perf MEDIUM-1 / LOW-1).</b> The probe runs OUTSIDE Caffeine:
 * {@code getIfPresent} → JDBC probe → {@code put} on a positive. A {@code get(key, loader)} would run the
 * blocking query inside Caffeine's synchronized {@code compute}, pinning the virtual-thread carrier on JDK 21
 * for every unknown-slug 404. Trade-off: no single-flight on a cold retired key — a concurrent burst may run
 * a few duplicate primary-key lookups before the first {@code put}; after it, none. With no loader there is
 * also no {@code null}-returning load, so the cache's recorded load/load-failure stats stay at zero.
 *
 * <p><b>Spring {@link Cache} API, not the native handle.</b> Lookups go through {@link Cache#get(Object)} and
 * {@link Cache#put(Object, Object)}; on a loader-less {@link CaffeineCache} these delegate straight to Caffeine's
 * {@code getIfPresent} / {@code put}, so the semantics above are unchanged. Not unwrapping
 * {@code getNativeCache()} keeps native cache access confined to {@code MasterCachePrefixEvictor}
 * ({@code CacheKeyScanOwnershipTest}).
 */
@Component
public class RetiredBookingSlugGuard {

    /** Caffeine cache of confirmed tombstones: key {@code "<kind>:<slug_hash>"}, value {@link Boolean#TRUE}. */
    public static final String KNOWN_RETIRED_CACHE = "retired-booking-slugs";

    private static final char KEY_SEPARATOR = ':';

    private final RetiredBookingSlugRepository retiredBookingSlugRepository;
    private final Cache knownRetired;

    public RetiredBookingSlugGuard(RetiredBookingSlugRepository retiredBookingSlugRepository,
                                   CacheManager cacheManager) {
        this.retiredBookingSlugRepository = retiredBookingSlugRepository;
        this.knownRetired = resolveKnownRetiredCache(cacheManager);
    }

    /** Throws {@link GoneException} (→ 410) when {@code slug} was retired in {@code kind}'s namespace. */
    public void throwGoneIfRetired(BookingLinkKind kind, String slug) {
        String slugHash = RetiredSlugHasher.hash(kind, slug);
        String cacheKey = kind.name() + KEY_SEPARATOR + slugHash;
        if (knownRetired.get(cacheKey) != null) {
            throw GoneException.bookingLinkRetired();
        }
        if (retiredBookingSlugRepository.existsByKindAndSlugHash(kind, slugHash)) {
            knownRetired.put(cacheKey, Boolean.TRUE);
            throw GoneException.bookingLinkRetired();
        }
    }

    private static Cache resolveKnownRetiredCache(CacheManager cacheManager) {
        if (!(cacheManager.getCache(KNOWN_RETIRED_CACHE) instanceof CaffeineCache caffeineCache)) {
            throw new IllegalStateException("Cache '" + KNOWN_RETIRED_CACHE + "' must be a registered Caffeine cache");
        }
        return caffeineCache;
    }
}
