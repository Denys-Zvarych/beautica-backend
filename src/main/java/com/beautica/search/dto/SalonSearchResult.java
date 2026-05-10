package com.beautica.search.dto;

import java.util.UUID;

/**
 * Outbound projection record for the salon listing endpoint
 * (e.g. {@code GET /api/v1/salons/search}).
 *
 * <p>Returns the minimal public-facing salon view used in search and
 * directory listings: identity, display name, geographic placement,
 * and an optional avatar URL.</p>
 *
 * <p>This is a response DTO only — no Bean Validation annotations
 * apply. Assembled by the search service from a projection query;
 * the JPA entity is never exposed directly.</p>
 */
public record SalonSearchResult(
        UUID salonId,
        String name,
        String city,
        String region,
        String avatarUrl
) {
}
