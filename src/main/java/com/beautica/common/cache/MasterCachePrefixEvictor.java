package com.beautica.common.cache;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * The single home for "evict every entry of these caches whose key starts with this masterId", run
 * <b>off the request thread</b> (Perf MEDIUM-3).
 *
 * <p><b>Why by prefix at all.</b> Every master-availability cache is keyed by a SpEL inline list whose
 * FIRST element is the masterId ({@code {#masterId, #from, #to}}, {@code {#masterId, #masterServiceId,
 * #from, #to}}, …). The window portion of those keys cannot be evicted per-date — a booking or schedule
 * change anywhere in the master's horizon can flip any window's verdict — so the only correct scope is
 * "all keys for this master". That is a Caffeine keyset scan, bounded to one master (never a blanket
 * {@code clear()} — Anti-Bug §F-6).
 *
 * <p><b>Why off-thread.</b> The scan is O(cacheSize), and it used to run SYNCHRONOUSLY on the committing
 * request thread ({@code afterCommit} callbacks run on the committer). A booking write scanned 2 caches,
 * a schedule write 5 (~5 500 keys), and a service-definition mutation multiplied that by every performing
 * master (a 50-master salon ≈ 200 000 key comparisons on one request). Worse,
 * {@code asMap().keySet().removeIf} takes the ConcurrentHashMap bin lock, while {@code sync = true}'s
 * {@code computeIfAbsent} HOLDS that same bin lock across its DB round-trips to Neon — so an eviction scan
 * could stall the booking-create thread for a concurrent reader's DB latency. The scan itself was correct;
 * it simply must not sit on the booking-create critical path.
 *
 * <p><b>Ordering is preserved.</b> Callers still invoke this from a {@code TransactionSynchronization
 * .afterCommit()} hook, so the DB write is committed and visible BEFORE eviction is even submitted. The
 * async hop can only DELAY the eviction by a few milliseconds past commit — never move it before commit —
 * so a reader that repopulates the cache during that window reads the already-committed (fresh) row. The
 * pre-fix hazard (evicting before commit and letting a parallel reader re-cache stale data — Anti-Bug §F-2)
 * is structurally impossible here.
 *
 * <p><b>Executor.</b> {@code cacheEvictionExecutor} (see {@code AsyncConfig}), which deliberately uses
 * {@code CallerRunsPolicy} — the one place in this codebase where that policy is correct. The Anti-Bug §H-2
 * ban targets SMTP/notification pools, where caller-runs re-introduces synchronous network I/O on the
 * request thread. Here the fallback work is a pure in-memory scan, and the alternative (AbortPolicy) would
 * DROP an eviction and serve stale availability for the full 60-second TTL — re-opening the very bug this
 * cache invalidation exists to prevent. Under saturation this degrades to exactly the pre-fix behaviour;
 * it can never be worse.
 */
@Component
public class MasterCachePrefixEvictor {

    private final CacheManager cacheManager;

    public MasterCachePrefixEvictor(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    /**
     * Evicts every entry of each named cache whose key's first element is {@code masterId}. Must be called
     * from an {@code afterCommit} hook (never inline in a write transaction).
     */
    @Async("cacheEvictionExecutor")
    public void evictByMasterPrefix(UUID masterId, String... cacheNames) {
        evictByKeyPrefixNow(masterId, cacheNames);
    }

    /**
     * Synchronous variant of {@link #evictByMasterPrefix}, for callers that must observe the eviction
     * before returning.
     *
     * <p>Identical scan and key predicate — it exists so the (subtle, previously-miswritten) key-shape
     * check has exactly ONE implementation in the codebase. Five call sites in {@code MasterService},
     * {@code BookingService} and {@code ServiceCatalogService} each carried their own hand-rolled copy
     * that tested {@code instanceof SimpleKey}; because every one of those caches declares an explicit
     * SpEL {@code key = "{...}"} (runtime type {@link java.util.ArrayList}, never {@code SimpleKey}),
     * all five {@code removeIf} predicates were unsatisfiable and every eviction was a silent no-op.
     * Delegating here instead of re-copying the corrected predicate is what stops that recurring.
     *
     * <p>{@code keyPrefix} is the FIRST element of the cache key, which is not always a masterId —
     * {@code revenue-dashboard} is keyed {@code {#actorId, …}}. The match is on key position, not on
     * the identifier's domain meaning.
     *
     * <p>Prefer {@link #evictByMasterPrefix} on request-serving paths; this variant runs the O(cacheSize)
     * scan on the calling thread.
     */
    public void evictByKeyPrefixNow(UUID keyPrefix, String... cacheNames) {
        for (String cacheName : cacheNames) {
            evictOne(cacheName, keyPrefix);
        }
    }

    /**
     * {@code @Cacheable} with an explicit SpEL {@code key} (e.g. {@code key = "{#masterId, #from, #to}"})
     * is never wrapped in a {@link org.springframework.cache.interceptor.SimpleKey} — that type is only
     * produced by the default {@code SimpleKeyGenerator} when no {@code key} attribute is given. The
     * {@code {...}} inline-list SpEL literal evaluates to a {@link List} (an {@code ArrayList}) at runtime,
     * so eviction matches on the real key's runtime type — {@link List} — and compares its first element
     * (the masterId) directly, rather than an {@code instanceof SimpleKey} check (which never matches these
     * keys) or fragile {@code toString()} substring matching.
     *
     * <p>A non-Caffeine {@link Cache} implementation exposes no keyset, so it falls back to
     * {@link Cache#clear()} — correct, if coarser.
     */
    private void evictOne(String cacheName, UUID keyPrefix) {
        Cache springCache = cacheManager.getCache(cacheName);
        if (springCache == null) {
            return;
        }
        Object nativeCache = springCache.getNativeCache();
        if (nativeCache instanceof com.github.benmanes.caffeine.cache.Cache<?, ?> caffeineCache) {
            caffeineCache.asMap().keySet().removeIf(k ->
                    k instanceof List<?> keyParts
                            && !keyParts.isEmpty()
                            && keyPrefix.equals(keyParts.get(0)));
        } else {
            springCache.clear();
        }
    }
}
