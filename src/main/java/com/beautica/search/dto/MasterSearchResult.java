package com.beautica.search.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Outbound projection record for {@code GET /api/v1/masters/search}.
 *
 * <p>Returns a flattened, denormalised view of a master suitable for the
 * search results list: identity, display name, location, aggregate rating,
 * an optional avatar URL, and the cheapest effective price across the
 * master's active services (used for client-side price filtering /
 * sorting).</p>
 *
 * <p>This is a response DTO only — no Bean Validation annotations apply.
 * It is intended to be assembled by the search service from a JPQL/SQL
 * projection query, not mapped from a JPA entity graph (avoids dragging
 * unrelated associations into the response).</p>
 */
public record MasterSearchResult(
        UUID masterId,
        UUID userId,
        String firstName,
        String lastName,
        String city,
        Double avgRating,
        Integer reviewCount,
        String avatarUrl,
        BigDecimal minEffectivePrice
) {
}
