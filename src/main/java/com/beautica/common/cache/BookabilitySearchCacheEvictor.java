package com.beautica.common.cache;

import com.beautica.search.service.SearchCacheNames;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Clears a discovery surface's search caches when a write flips a provider's search membership under
 * the bookability rule ({@code MasterBookabilitySql}: ≥1 active service AND a schedule). Invoked ONLY
 * by {@code MasterSearchVisibilityGuard}, which evaluates the single-master verdict before and after
 * the write inside the transaction and calls this afterCommit only for masters whose verdict actually
 * changed — so a day-off override, a price-band edit, a photo or a definition edit never clears
 * anything (perf audit 2026-10-05, finding 1).
 *
 * <p><b>Cleared per surface, not evicted per key</b> — the same rule {@code MasterProfileCacheEvictor},
 * {@code SalonService} and {@code UserService} apply: search keys are the request tuple (filters,
 * query, page), so no key names a provider. The narrowest available scope is the surface the master
 * is listed on (salon search for a salon-attached master, master search for an independent one),
 * never both. Every search {@code @Cacheable} is {@code sync = true}, so the refill cannot herd.
 *
 * <p><b>Totals included (finding 4).</b> The surface's {@code search:*:total} memo (filter-scoped
 * row counts {@code SearchService} reuses for deep pages) is cleared with the page caches: a stale
 * total after a membership flip makes an out-of-range page short-circuit on the wrong count.
 *
 * <p><b>Callers own the ordering.</b> The guard calls this from an {@code afterCommit} hook
 * (Anti-Bug §F-2), so it runs synchronously and does no transaction bookkeeping of its own.
 */
@Component
public class BookabilitySearchCacheEvictor {

    private static final List<String> SALON_SURFACE = List.of(
            SearchCacheNames.SALONS_BROWSE, SearchCacheNames.SALONS_QUERY, SearchCacheNames.SALONS_TOTAL);
    private static final List<String> MASTER_SURFACE = List.of(
            SearchCacheNames.MASTERS_BROWSE, SearchCacheNames.MASTERS_QUERY, SearchCacheNames.MASTERS_TOTAL);

    private final CacheManager cacheManager;

    public BookabilitySearchCacheEvictor(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    /** Clears salon search and its totals memo — the surface every salon-attached master is listed on. */
    public void clearSalonSearch() {
        clear(SALON_SURFACE);
    }

    /** Clears master search and its totals memo — the surface independent masters are listed on. */
    public void clearMasterSearch() {
        clear(MASTER_SURFACE);
    }

    private void clear(List<String> cacheNames) {
        for (String cacheName : cacheNames) {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache != null) {
                cache.clear();
            }
        }
    }
}
