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
 *
 * <h4>{@code categoryCode} / {@code categoryLabel} — the FILTER axis, not a card field</h4>
 * The identical pair {@link FavoriteMasterResponse} carries, and identical for a reason: the
 * approved design puts one category axis across BOTH kinds so a single chip row filters the
 * whole screen. Derived from the platform category of the service in this client's most recent
 * booking <b>at that salon</b> ({@code bookings.salon_id} as stamped at creation, so a master
 * who has since moved salons does not drag their history with them).
 *
 * <p>The salon arm is therefore <b>not null-by-design</b> — it is null only when this client has
 * no booked history at that salon. As on the master arm that is common, because favouriting
 * normally precedes booking, and it is accepted design behaviour rather than a defect: the
 * client hides categories with no rows. Both fields are always present together or both
 * {@code null}. See {@link FavoriteMasterResponse}'s javadoc for the full rationale, the
 * batching guarantee, and the recorded decision not to make this field plural.
 *
 * @param categoryCode  {@code platform_categories.name} of the last service booked at this
 *                      salon by this client, {@code null} when there is none
 * @param categoryLabel that category's Ukrainian {@code display_name}, {@code null} likewise
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
        String locationNote,
        String categoryCode,
        String categoryLabel
) {
}
