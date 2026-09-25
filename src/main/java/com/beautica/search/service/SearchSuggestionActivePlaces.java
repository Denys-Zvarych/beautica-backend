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
 * Audit-fix cycle 1, finding 2 (LOW security + perf) — the cache-key-poisoning fix for
 * {@code GET /api/v1/search/suggestions}'s per-place availability cache.
 *
 * <h3>The problem this closes</h3>
 * {@code SearchSuggestionAvailability}'s {@code searchSuggestionAvailability} cache is keyed on
 * {@code (cityId, districtId)} — an attacker-choosable pair. Before this fix, ANY well-formed but
 * unknown UUID reached {@code SearchSuggestionAvailabilityRepository#findAvailable}: one UNION
 * query per random id, AND one new entry in the bounded 1 000-slot cache, evicting a real place's
 * hot entry under sustained cycling.
 *
 * <h3>The fix</h3>
 * ONE cached "which places are active at all" set — city ids and district ids that have ≥1
 * bookable offer, computed by the exact same D3 predicates {@code findAvailable} uses (reused via
 * {@link SearchSuggestionAvailabilityRepository#findActivePlaces()}), on the SAME 10-minute TTL.
 * {@code SearchSuggestionService} consults this FIRST: a request whose place is not in the active
 * set returns {@code PlaceAvailability.EMPTY} immediately — no repository call, no per-place cache
 * entry. The per-place cache then only ever holds entries for places this snapshot already proved
 * are real, so its 1 000-slot bound is spent on genuine data, not attacker input. The national
 * entry ({@code SuggestionPlaceKey.isNational()}) is exempt from this gate — it is one fixed key,
 * not attacker-cyclable, and must still resolve to the real (possibly empty) national list.
 *
 * <p><b>Cache: {@code searchSuggestionActivePlaces}</b> (declared in {@code CacheConfig}, 1 entry,
 * 10-minute TTL, {@code sync = true} — same reasoning as {@link SearchSuggestionCatalogue}'s single
 * hot key: this is now consulted on EVERY non-national request, so a TTL-expiry herd must collapse
 * to one reload). Separate bean from {@link SearchSuggestionAvailability} so
 * {@code SearchSuggestionService} calling both is two ordinary cross-bean calls, never a
 * self-invocation (§F-3).
 */
@Component
public class SearchSuggestionActivePlaces {

    /**
     * Registered, bounded and metered in {@code CacheConfig} (another package, hence public): 1
     * entry, 10-minute TTL.
     */
    public static final String CACHE_NAME = "searchSuggestionActivePlaces";

    private final SearchSuggestionAvailabilityRepository repository;

    public SearchSuggestionActivePlaces(SearchSuggestionAvailabilityRepository repository) {
        this.repository = repository;
    }

    @Cacheable(value = CACHE_NAME, sync = true)
    @Transactional(readOnly = true)
    ActivePlaces snapshot() {
        List<Object[]> rows = repository.findActivePlaces();
        if (rows.isEmpty()) {
            return ActivePlaces.EMPTY;
        }
        Set<UUID> cityIds = new HashSet<>();
        Set<UUID> districtIds = new HashSet<>();
        for (Object[] row : rows) {
            UUID cityId = (UUID) row[0];
            if (cityId != null) {
                cityIds.add(cityId);
            }
            UUID districtId = (UUID) row[1];
            if (districtId != null) {
                districtIds.add(districtId);
            }
        }
        return new ActivePlaces(Set.copyOf(cityIds), Set.copyOf(districtIds));
    }
}
