package com.beautica.location;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * The single forward-compat seam (mitigation <b>M2</b>) through which all
 * discovery code obtains locality information.
 *
 * <h3>Why this seam exists (M2 rationale — see Phase 10.5 doc §11)</h3>
 * Today discovery is a pure FK match on {@code district_id} (district-primary)
 * falling back to {@code city_id}. Part B introduces geocoded point/radius
 * search (lat/lng + a distance bound). When Part B lands, <em>only the
 * implementation behind this interface changes</em> — the resolver starts
 * deriving the discovery predicate from coordinates instead of the taxonomy FK
 * pair. Search and any future booking code that needs "where is this provider
 * discovered?" call this seam and never read the {@code district_id} /
 * {@code city_id} columns directly. That keeps the mobile/search contract from
 * churning a second time and confines the Part B blast radius to one class.
 *
 * <h3>Two responsibilities, both narrow</h3>
 * <ol>
 *   <li>{@link #resolveFilter(UUID, UUID)} — turn an inbound request location
 *       filter (chosen city / optional district) into the
 *       {@link DiscoveryLocationKey} the query layer filters on, applying the
 *       district-primary rule on the read side (an unspecified district on a
 *       districted city simply widens to city-level results — write-side
 *       enforcement is Phase 10.6, not here).</li>
 *   <li>{@link #resolveLabels(Collection, Collection)} — batch-resolve the
 *       human-readable {@code name_uk} labels for an entire result page in a
 *       fixed number of queries (§E: no per-row N+1), so the mobile client
 *       needs no extra round-trip.</li>
 * </ol>
 */
public interface DiscoveryLocationResolver {

    /**
     * Resolves an inbound request location filter into the discovery-locality
     * key the query layer filters on.
     *
     * <p>District-primary, read side: if a {@code districtId} is supplied it
     * wins; otherwise the {@code cityId} is the filter. Supplying a city that
     * has urban districts <em>without</em> a district does not error here — it
     * widens results to the whole city. (Write-side "districted city requires
     * a district" enforcement is Phase 10.6.)
     *
     * @param cityId     chosen city id, or {@code null} for no location filter
     * @param districtId chosen urban-district id, or {@code null}
     * @return a {@link DiscoveryLocationKey}, or {@code null} when no location
     *         filter was supplied (both ids {@code null})
     */
    DiscoveryLocationKey resolveFilter(UUID cityId, UUID districtId);

    /**
     * Batch-resolves the {@code name_uk} display labels for every distinct
     * city / district id appearing on a result page.
     *
     * <p>Exactly two queries regardless of page size (one over the city ids,
     * one over the district ids); each is skipped entirely when its id set is
     * empty. Callers stamp the returned maps onto their result DTOs in-memory
     * — never a per-row taxonomy lookup (§E).
     *
     * @param cityIds     distinct city ids on the page (nulls pre-filtered by
     *                    the caller)
     * @param districtIds distinct district ids on the page (nulls pre-filtered)
     * @return resolved {@code name_uk} labels keyed by id
     */
    DiscoveryLabels resolveLabels(Collection<UUID> cityIds, Collection<UUID> districtIds);

    /**
     * Carrier for the two id→{@code name_uk} maps produced by
     * {@link #resolveLabels(Collection, Collection)}. Lookups for an absent id
     * return {@code null} (a provider with no resolved city/district yields a
     * {@code null} label, which the result DTO surfaces as-is).
     *
     * @param cityLabels     city id → Ukrainian city name
     * @param districtLabels district id → Ukrainian district name
     */
    record DiscoveryLabels(
            Map<UUID, String> cityLabels,
            Map<UUID, String> districtLabels
    ) {
        public String cityLabel(UUID cityId) {
            return cityId == null ? null : cityLabels.get(cityId);
        }

        public String districtLabel(UUID districtId) {
            return districtId == null ? null : districtLabels.get(districtId);
        }
    }
}
