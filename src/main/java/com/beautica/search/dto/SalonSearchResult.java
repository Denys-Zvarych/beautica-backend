package com.beautica.search.dto;

import java.math.BigDecimal;
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
        BigDecimal priceMax
) {
}
