package com.beautica.search.service;

import java.util.UUID;

/**
 * Cache key for {@link SearchSuggestionAvailability#forPlace(SuggestionPlaceKey)} (Phase 331) —
 * district-primary, normalised exactly like {@code SearchService.appendWhereClause}'s read side
 * ({@link com.beautica.location.TaxonomyDiscoveryLocationResolver#resolveFilter}: a supplied
 * district wins over any city).
 *
 * <p>Normalisation collapses {@code (cityId, districtId)} onto ONE of two shapes:
 * <ul>
 *   <li>a district is present → {@code (null, districtId)}, dropping the city entirely, so
 *       {@code cityId=A&districtId=D1} and {@code districtId=D1} (no city) hit the SAME cache
 *       entry — city is ignored for filtering the moment a district is chosen, exactly as
 *       {@code SearchService} already behaves;</li>
 *   <li>no district → {@code (cityId, null)}, or {@code (null, null)} for the national entry
 *       (no place chosen at all).</li>
 * </ul>
 */
record SuggestionPlaceKey(UUID cityId, UUID districtId) {

    static SuggestionPlaceKey of(UUID cityId, UUID districtId) {
        return districtId != null
                ? new SuggestionPlaceKey(null, districtId)
                : new SuggestionPlaceKey(cityId, null);
    }

    boolean isNational() {
        return cityId == null && districtId == null;
    }
}
