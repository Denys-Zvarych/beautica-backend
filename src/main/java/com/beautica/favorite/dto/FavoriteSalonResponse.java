package com.beautica.favorite.dto;

import java.util.UUID;

/**
 * A favorited salon, for {@code GET /api/v1/favorites/salons}.
 *
 * <p>Mirrors the public salon-card shape: resolved locality labels
 * ({@code cityLabel}/{@code districtLabel}, the taxonomy {@code name_uk};
 * {@code districtLabel} null for non-districted/unset locality) rather than raw
 * FK UUIDs.
 *
 * @param avgRating salon's aggregate rating, {@code null} when never reviewed
 */
public record FavoriteSalonResponse(
        UUID salonId,
        String name,
        String avatarUrl,
        String cityLabel,
        String districtLabel,
        Double avgRating
) {
}
