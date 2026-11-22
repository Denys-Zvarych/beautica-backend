package com.beautica.catalog;

import com.beautica.search.service.SearchSuggestionActivePlaces;
import com.beautica.search.service.SearchSuggestionAvailability;
import com.beautica.search.service.SearchSuggestionCatalogue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/**
 * The ONE call that empties every platform-catalog cache (B501, track D2, SVC-53): catalog-admin writes are rare and
 * by hand, and catalog reads are cheap to refill, so each write evicts everything rather than a hand-picked subset.
 *
 * <p><b>Ordering (Anti-Bug §F-2).</b> {@link #evictAll} registers an {@code afterCommit} hook when a transaction is
 * active, so a parallel reader cannot repopulate a cache from pre-commit state; a rolled-back write evicts nothing.
 * Outside a transaction it evicts immediately.
 *
 * <p><b>Why {@code invalidate()} and not per-key (§F-6).</b> Every cache here is low-cardinality reference data
 * (60-min / 5-min TTL) on a write path that runs a handful of times a day; {@code invalidate()} also drops entries
 * immediately rather than deferring like {@code clear()} may.
 *
 * <p><b>The list is pinned.</b> {@code CatalogCacheEvictorTest} compares {@link #CACHE_NAMES} with the catalog subset
 * of {@code CacheConfig}'s registered names, so a new catalog cache that is not added here fails the build.
 * The per-salon {@code salon-service-catalog} / {@code salon-catalog-snapshot} caches are deliberately NOT here: they
 * are keyed per salon, owned by {@code SalonCatalogCacheEvictor}, and their 60-s TTL bounds a catalog rename's lag.
 * The existing approve/create {@code @CacheEvict} sets are NOT rewired onto this (track D5, additive only).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CatalogCacheEvictor {

    /**
     * {@code platform-category-order} is {@code PlatformCategoryOrderLookup.CACHE_NAME} (package-private there), and
     * {@code approved-categories} is {@code CategoryRequestService.APPROVED_CATEGORIES_CACHE}; both are literals here
     * and kept honest by the pin test.
     */
    public static final List<String> CACHE_NAMES = List.of(
            "service-categories",
            "approved-categories",
            "service-types",
            "service-type-by-id",
            "service-type-search",
            "platform-category-order",
            SearchSuggestionCatalogue.CACHE_NAME,
            SearchSuggestionAvailability.CACHE_NAME,
            // The availability cache's gate (same D3 predicates): a stale set would short-circuit a place whose
            // offers an unhide just made visible to EMPTY for the 10-min TTL.
            SearchSuggestionActivePlaces.CACHE_NAME);

    private final CacheManager cacheManager;

    /** Empties every catalog cache after the current transaction commits (immediately outside one). */
    public void evictAll() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            evictNow();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                evictNow();
            }
        });
    }

    private void evictNow() {
        for (String name : CACHE_NAMES) {
            Cache cache = cacheManager.getCache(name);
            if (cache == null) {
                // Never throw: after commit, an exception would turn a committed write into a 500.
                log.warn("Catalog cache not registered, skipped: {}", name);
                continue;
            }
            cache.invalidate();
        }
    }
}
