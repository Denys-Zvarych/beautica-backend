package com.beautica.search.service;

import com.beautica.search.repository.SearchSuggestionAvailabilityRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The place-scoped half of {@code GET /api/v1/search/suggestions} (Phase 331): "a suggestion
 * never opens an empty results page for the same place" (D3), backed by ONE grouped query per
 * place per cache TTL.
 *
 * <p><b>Cache: {@code searchSuggestionAvailability}</b> (declared in {@code CacheConfig}, 1 000
 * entries max, 10-minute TTL). Key = {@link SuggestionPlaceKey}, already district-primary
 * normalised by {@link SuggestionPlaceKey#of}, so {@code (A, D1)} and {@code (null, D1)} share one
 * entry and {@code (null, null)} is the single national entry. Deliberately NOT {@code sync = true}
 * — unlike {@link SearchSuggestionCatalogue}'s single hot key, this cache has hundreds of distinct
 * keys and misses on every new place; a sync load would pin a virtual-thread carrier under
 * Caffeine's bin lock for the whole miss (same reasoning as {@code SettlementSearchService
 * #runIndexedSearch}).
 *
 * <p><b>TTL-only invalidation, accepted staleness</b> (D5): no write path evicts this cache. A
 * master becoming bookable, a new salon service, or a deactivation is reflected within ≤10 minutes,
 * matching the {@code service-types}/{@code service-categories} 60-min caches' staleness contract
 * (this one is tighter). The results page itself is always live, so the worst case is one stale
 * suggestion or one missing suggestion for up to 10 minutes — never a wrong BOOKING.
 *
 * <p><b>Unknown place ids</b> resolve to an empty {@link PlaceAvailability} (0 categories, 0
 * types) rather than an error or a fallback to the national list — the repository's UNION simply
 * matches no rows, exactly like {@code /search/masters}/{@code /search/salons} return an empty
 * page for an unknown-but-well-formed {@code location.cityId}. Empty results are cached too.
 */
@Component
public class SearchSuggestionAvailability {

    /**
     * Registered, bounded and metered in {@code CacheConfig} (another package, hence public):
     * 1 000 entries max, 10-minute TTL.
     */
    public static final String CACHE_NAME = "searchSuggestionAvailability";

    private final SearchSuggestionAvailabilityRepository repository;

    public SearchSuggestionAvailability(SearchSuggestionAvailabilityRepository repository) {
        this.repository = repository;
    }

    @Cacheable(value = CACHE_NAME)
    @Transactional(readOnly = true)
    PlaceAvailability forPlace(SuggestionPlaceKey key) {
        List<Object[]> rows = repository.findAvailable(key.cityId(), key.districtId());
        if (rows.isEmpty()) {
            return PlaceAvailability.EMPTY;
        }
        Set<String> categoryKeys = new HashSet<>();
        Set<UUID> serviceTypeIds = new HashSet<>();
        for (Object[] row : rows) {
            String category = (String) row[0];
            if (category != null) {
                categoryKeys.add(category);
            }
            UUID serviceTypeId = (UUID) row[1];
            if (serviceTypeId != null) {
                serviceTypeIds.add(serviceTypeId);
            }
        }
        return new PlaceAvailability(Set.copyOf(categoryKeys), Set.copyOf(serviceTypeIds));
    }
}
