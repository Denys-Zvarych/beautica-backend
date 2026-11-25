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
import java.util.stream.Stream;

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
 * <p><b>What is evicted.</b> (1) The platform-catalog reference caches: {@code service-categories},
 * {@code approved-categories}, {@code service-types}, {@code service-type-by-id}, {@code service-type-search},
 * {@code platform-category-order}, {@code platform-category-labels} and the three search-suggestion caches.
 * (2) Since B504 (track D18) the two per-master read caches that embed a category display name
 * ({@code categoryDisplayName}): {@code masterServices} (10-min TTL, {@code GET /masters/{id}/services}) and
 * {@code booking-slug-info} (60 s, {@code GET /book/{slug}/info}). They are emptied whole, not per master: a rename
 * can touch every master, and portal writes run a few times a day.
 *
 * <p><b>What is deliberately left.</b> The per-salon {@code salon-service-catalog} / {@code salon-catalog-snapshot}
 * caches (owned by {@code SalonCatalogCacheEvictor}; their 60-s TTL bounds a rename's lag on the salon catalogue).
 * {@code client-passport} holds no category label (the timeline is uncached). The existing approve/create
 * {@code @CacheEvict} sets are NOT rewired onto this (track D5, additive only).
 *
 * <p><b>The list is pinned.</b> {@code CatalogCacheEvictorTest} compares {@link #CACHE_NAMES} with the catalog subset
 * of {@code CacheConfig}'s registered names plus {@link #LABEL_HOLDING_CACHES}, so a new catalog cache that is not
 * added here fails the build.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CatalogCacheEvictor {

    /**
     * B504 (track D18) — per-master read caches outside the catalog naming pattern that embed a category display name;
     * part of {@link #CACHE_NAMES}.
     */
    public static final List<String> LABEL_HOLDING_CACHES = List.of("masterServices", "booking-slug-info");

    /**
     * {@code platform-category-order} is {@code PlatformCategoryOrderLookup.CACHE_NAME} (package-private there), and
     * {@code approved-categories} is {@code CategoryRequestService.APPROVED_CATEGORIES_CACHE}; both are literals here
     * and kept honest by the pin test.
     */
    public static final List<String> PLATFORM_CATALOG_CACHES = List.of(
            "service-categories",
            "approved-categories",
            "service-types",
            "service-type-by-id",
            "service-type-search",
            "platform-category-order",
            // B504 (D18): PlatformCategoryOrderLookup.LABELS_CACHE_NAME (package-private there).
            "platform-category-labels",
            SearchSuggestionCatalogue.CACHE_NAME,
            SearchSuggestionAvailability.CACHE_NAME,
            // The availability cache's gate (same D3 predicates): a stale set would short-circuit a place whose
            // offers an unhide just made visible to EMPTY for the 10-min TTL.
            SearchSuggestionActivePlaces.CACHE_NAME);

    /** Every cache {@link #evictAll} empties: the platform-catalog caches, then {@link #LABEL_HOLDING_CACHES}. */
    public static final List<String> CACHE_NAMES =
            Stream.concat(PLATFORM_CATALOG_CACHES.stream(), LABEL_HOLDING_CACHES.stream()).toList();

    private final CacheManager cacheManager;

    /** Empties every catalog cache after the current transaction commits (immediately outside one). */
    public void evictAll() {
        evictAfterCommit(CACHE_NAMES);
    }

    /**
     * B505 — empties only {@link #PLATFORM_CATALOG_CACHES}, leaving the hot per-master {@link #LABEL_HOLDING_CACHES}
     * warm. For a delete of an UNUSED category / type: no service definition references the row, so no
     * {@code masterServices} / {@code booking-slug-info} entry can hold its label. Since B545 also the portal approve
     * of a category request / type suggestion: approve only adds a category or a type (no label of an existing row
     * changes, no {@code ServiceDefinition} is created), so no per-master entry can be stale. Same after-commit
     * ordering as {@link #evictAll}.
     */
    public void evictPlatformCatalog() {
        evictAfterCommit(PLATFORM_CATALOG_CACHES);
    }

    private void evictAfterCommit(List<String> names) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            evictNow(names);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                evictNow(names);
            }
        });
    }

    private void evictNow(List<String> names) {
        for (String name : names) {
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
