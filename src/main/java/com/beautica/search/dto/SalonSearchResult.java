package com.beautica.search.dto;

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
 * <p>This is a response DTO only — no Bean Validation annotations
 * apply. Assembled by the search service from a projection query;
 * the JPA entity is never exposed directly.</p>
 */
public record SalonSearchResult(
        UUID salonId,
        String name,
        String cityLabel,
        String districtLabel,
        String avatarUrl
) {
}
