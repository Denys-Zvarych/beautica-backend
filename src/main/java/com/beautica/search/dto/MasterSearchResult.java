package com.beautica.search.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Outbound projection record for {@code GET /api/v1/masters/search}.
 *
 * <p>Returns a flattened, denormalised view of a master suitable for the
 * search results list: identity, display name, resolved locality labels,
 * aggregate rating, an optional avatar URL, and the cheapest effective price
 * across the master's active services (used for client-side price filtering /
 * sorting).</p>
 *
 * <p><b>Phase 10.5 — resolved locality labels:</b> the legacy free-text
 * {@code city} field is replaced by {@code cityLabel} + {@code districtLabel},
 * the taxonomy {@code name_uk} of the master's discovery locality (district
 * when the master's city is districted, else city). They are batch-resolved
 * by the {@code DiscoveryLocationResolver} M2 seam so the mobile client
 * renders human-readable locality with <b>no extra round-trip</b>;
 * {@code districtLabel} is {@code null} for non-districted cities or unset
 * locality. The internal city/district UUIDs are intentionally NOT exposed —
 * this is a {@code permitAll} endpoint and only the display labels are public
 * (§I).</p>
 *
 * <p>This is a response DTO only — no Bean Validation annotations apply.
 * It is intended to be assembled by the search service from a JPQL/SQL
 * projection query, not mapped from a JPA entity graph (avoids dragging
 * unrelated associations into the response).</p>
 *
 * <p>{@code userId} is intentionally absent: this record is returned from
 * a {@code permitAll} endpoint, and exposing internal user UUIDs would
 * enable enumeration of the user table by anonymous callers.</p>
 */
public record MasterSearchResult(
        UUID masterId,
        String firstName,
        String lastName,
        String cityLabel,
        String districtLabel,
        Double avgRating,
        Integer reviewCount,
        String avatarUrl,
        BigDecimal minEffectivePrice
) {
}
