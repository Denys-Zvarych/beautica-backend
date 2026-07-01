package com.beautica.search.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Outbound projection record for the salon listing endpoint
 * (e.g. {@code GET /api/v1/salons/search}).
 *
 * <p>Returns the minimal public-facing salon view used in search and
 * directory listings: identity, display name, resolved locality labels,
 * and an optional avatar URL.</p>
 *
 * <p><b>Phase 10.5 — resolved locality labels:</b> the legacy free-text
 * {@code city} / {@code region} fields are replaced by {@code cityLabel} +
 * {@code districtLabel}, the taxonomy {@code name_uk} of the salon's discovery
 * locality (district when the salon's city is districted, else city),
 * batch-resolved by the {@code DiscoveryLocationResolver} M2 seam — <b>no
 * extra client round-trip</b>. {@code districtLabel} is {@code null} for
 * non-districted cities or unset locality. The internal city/district UUIDs
 * are intentionally NOT exposed (§I — {@code permitAll} endpoint).</p>
 *
 * <p><b>Phase 19.7 — salon price range (decision 5):</b> {@code priceMin} /
 * {@code priceMax} carry the price band across the salon's masters' active
 * services. {@code priceMin} is the lowest service floor
 * ({@code MIN(base_price)}); {@code priceMax} is the highest ceiling
 * ({@code MAX(price_max)} for {@code RANGE} services, else {@code base_price}
 * for {@code FIXED}) — mirroring the per-service pricing model used by
 * {@code masters.min_effective_price}. When the search carries a category
 * filter the aggregation is scoped to matching services; otherwise it is
 * salon-wide. <b>Both are {@code null}</b> when the salon has no active,
 * priced services (mobile renders no price). The two values are equal when the
 * band collapses to a single number — collapsing to one display value is a
 * mobile-rendering concern; the backend always returns both.</p>
 *
 * <p><b>{@code serviceNames} — service-name preview:</b> a short, capped
 * ({@code SERVICE_NAME_CAP}) list of the distinct active service names offered
 * across the salon's masters, surfaced so the result card can preview what the
 * salon does. Each name is the owner-set {@code service_definitions.name}. The
 * list is never {@code null}: a salon with no active, priced services yields an
 * empty list, serialised as {@code []} — mirroring the master
 * {@code serviceNames} contract. Carries no internal IDs, so it is safe on this
 * {@code permitAll} endpoint (§I).</p>
 *
 * <p><b>{@code street} / {@code buildingNo} / {@code locationNote} — AUTH-GATED
 * street address:</b> the salon's full street-level address
 * ({@code salons.street} / {@code salons.building_no} /
 * {@code salons.location_note}), returned <b>only to an authenticated
 * caller</b>. For an anonymous request all three are {@code null}; the public
 * {@code cityLabel}/{@code districtLabel} remain visible to everyone.
 * {@code locationNote} is a free-text arrival hint ("entrance from the yard")
 * and may be {@code null} even for an authenticated caller when the salon
 * recorded none. The values are always computed in SQL and cached on the full
 * object; the auth-gate (nulling for anon) happens per-request <em>after</em>
 * the cache read in the controller, so a warm cache can never leak addresses
 * across the anon/authenticated boundary. See {@code SearchController} for the
 * strip.</p>
 *
 * <p>This is a response DTO only — no Bean Validation annotations
 * apply. Assembled by the search service from a projection query;
 * the JPA entity is never exposed directly.</p>
 */
public record SalonSearchResult(
        UUID salonId,
        String name,
        String cityLabel,
        String districtLabel,
        String avatarUrl,
        BigDecimal priceMin,
        BigDecimal priceMax,
        List<String> serviceNames,
        String street,
        String buildingNo,
        String locationNote,
        List<String> matchedServiceNames
) {

    /**
     * Returns a copy with the auth-gated street-level fields ({@code street},
     * {@code buildingNo}, {@code locationNote}) nulled out, for serving to
     * anonymous callers. All other fields — including the public locality
     * labels — are preserved.
     *
     * <p>Applied per-request in the controller <em>after</em> the
     * {@code @Cacheable} read so the cache always holds the full object and
     * never leaks addresses across the anon/authenticated boundary.</p>
     */
    public SalonSearchResult withoutStreetAddress() {
        return new SalonSearchResult(
                salonId, name, cityLabel, districtLabel, avatarUrl,
                priceMin, priceMax, serviceNames, null, null, null, matchedServiceNames
        );
    }
}
