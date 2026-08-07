package com.beautica.client.repository;

import java.util.UUID;

/**
 * A city FK id and the client's COMPLETED-booking count there (Phase 244 / 31.1).
 *
 * <p>Structural twin of {@link DistrictCount} and must stay in lockstep with it.
 * Carries the <b>FK id only</b> — never a label. JPQL cannot resolve the taxonomy
 * {@code name_uk}; the service batch-resolves the id to a display label through the
 * {@code DiscoveryLocationResolver} M2 seam (occupied-territory ban: labels come only
 * from the joined, pre-filtered taxonomy, never a literal). The id is the discovery
 * city — salon presence wins outright via
 * {@code CASE WHEN s.id IS NOT NULL THEN s.cityId ELSE mu.cityId END}, never
 * {@code COALESCE} (see {@link ClientAggregationRepository#findTopCities}).
 *
 * @param cityId the discovery city FK id (never null — null ids are filtered out in SQL)
 * @param count  number of COMPLETED bookings in that city
 */
public record CityCount(
        UUID cityId,
        long count
) {
}
