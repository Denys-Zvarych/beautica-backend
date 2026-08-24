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
 * <p><b>{@code street} / {@code buildingNo} / {@code locationNote}</b> (mobile Phase 111) are the
 * salon's street address, read from the {@code salons} row. All three are nullable — the columns
 * carry no {@code NOT NULL} (V54).
 *
 * @param avgRating salon's aggregate rating, {@code null} when never reviewed. Read from the
 *                  persisted {@code salons.avg_rating} column — the same "recalculate on write,
 *                  read persisted on read" source {@code SalonReviewSummaryResponse} uses, NOT a
 *                  live {@code AVG(reviews.rating)} aggregate. That aggregate was removed from
 *                  the projection query when {@code ReviewRepository#recalculateSalonRating}
 *                  became the equal-weighted mean of the salon's masters' salon-scoped ratings:
 *                  a live per-review average would have printed a DIFFERENT number on this card
 *                  than the salon's own profile shows.
 */
public record FavoriteSalonResponse(
        UUID salonId,
        String name,
        String avatarUrl,
        String cityLabel,
        String districtLabel,
        Double avgRating,
        String street,
        String buildingNo,
        String locationNote
) {
}
