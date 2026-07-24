package com.beautica.location;

import com.beautica.location.repository.CityDistrictRepository;
import com.beautica.location.repository.CityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Part-A implementation of the {@link DiscoveryLocationResolver} M2 seam:
 * discovery locality is the Phase 10.1 taxonomy FK pair, district-primary.
 *
 * <p><b>This is the swappable class (M2).</b> When Part B (geocoded
 * point/radius search) lands, a new implementation derives the discovery
 * predicate from coordinates and replaces this bean — every call site
 * ({@code SearchService}, future booking code) keeps consuming
 * {@link DiscoveryLocationKey} / {@link DiscoveryLabels} unchanged. No call
 * site reads {@code district_id} / {@code city_id} columns directly; that
 * read happens here and only here.
 *
 * <p><b>No N+1 (§E):</b> {@link #resolveLabels} issues at most two
 * {@code IN (...)} queries for an entire page (one for cities, one for
 * districts), each skipped when its id set is empty. The label maps are then
 * stamped onto result DTOs in memory by the caller.
 *
 * <p><b>Not the cached {@code LocationQueryService}:</b> that cache is keyed
 * by the cascading-picker parents (oblast id / city id) for the mobile picker
 * tiers, not by arbitrary {@code (cityId, districtId)} sets drawn from a
 * search page. Reusing it would force per-oblast cache fan-out and a
 * post-filter; a direct batched {@code IN} resolve over the static taxonomy is
 * both simpler and a single round-trip per dimension.
 */
@Component
@RequiredArgsConstructor
public class TaxonomyDiscoveryLocationResolver implements DiscoveryLocationResolver {

    private final CityRepository cityRepository;
    private final CityDistrictRepository cityDistrictRepository;

    /**
     * District-primary, read side: a supplied {@code districtId} wins;
     * otherwise the {@code cityId} is the filter; both {@code null} → no
     * location filter. A districted city without a district is not an error
     * here — it widens to city-level results (write-side enforcement is
     * Phase 10.6).
     */
    @Override
    public DiscoveryLocationKey resolveFilter(UUID cityId, UUID districtId) {
        if (cityId == null && districtId == null) {
            return null;
        }
        return new DiscoveryLocationKey(cityId, districtId);
    }

    @Override
    @Transactional(readOnly = true)
    public DiscoveryLabels resolveLabels(Collection<UUID> cityIds, Collection<UUID> districtIds) {
        return new DiscoveryLabels(
                resolveCityLabels(cityIds),
                resolveDistrictLabels(districtIds));
    }

    private Map<UUID, String> resolveCityLabels(Collection<UUID> cityIds) {
        if (cityIds == null || cityIds.isEmpty()) {
            return Map.of();
        }
        return toLabelMap(cityRepository.findNameUkByIdIn(cityIds));
    }

    private Map<UUID, String> resolveDistrictLabels(Collection<UUID> districtIds) {
        if (districtIds == null || districtIds.isEmpty()) {
            return Map.of();
        }
        return toLabelMap(cityDistrictRepository.findNameUkByIdIn(districtIds));
    }

    private static Map<UUID, String> toLabelMap(Iterable<Object[]> rows) {
        Map<UUID, String> labels = new HashMap<>();
        for (Object[] row : rows) {
            labels.put((UUID) row[0], (String) row[1]);
        }
        return labels;
    }
}
