package com.beautica.location;

import java.util.UUID;

/**
 * The resolved <em>discovery locality</em> of a searchable provider — the
 * single locality unit a provider is discovered under.
 *
 * <p><b>District-primary rule (Phase 10.5):</b> where the provider's city has
 * urban districts, the {@code districtId} is the discovery unit; for cities
 * with no urban districts the {@code cityId} is the leaf. {@code cityId} is
 * always populated (every provider belongs to a city); {@code districtId} is
 * {@code null} for non-districted cities.
 *
 * <p><b>M2 seam value object.</b> This is the stable currency of
 * {@link DiscoveryLocationResolver}. Part B (geocoded point/radius search)
 * adds {@code lat}/{@code lng}/{@code radius} <em>behind the resolver</em>;
 * callers keep consuming this key (or its successor) without change. Search
 * and any future booking code obtain locality as a {@code DiscoveryLocationKey}
 * — they never read {@code district_id} columns directly.
 *
 * @param cityId     owning city id (never {@code null} for a resolved provider)
 * @param districtId urban-district id when the city is districted, else
 *                   {@code null}
 */
public record DiscoveryLocationKey(UUID cityId, UUID districtId) {

    /**
     * @return {@code true} when the discovery unit is the urban district
     *         (districted city); {@code false} when it is the city itself.
     */
    public boolean isDistrictPrimary() {
        return districtId != null;
    }
}
