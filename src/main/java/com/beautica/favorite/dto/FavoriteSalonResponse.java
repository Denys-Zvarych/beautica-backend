package com.beautica.favorite.dto;

import java.util.List;
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
 * <h4>{@code categories} — the FILTER axis, not a card field</h4>
 * The identical shape {@link FavoriteMasterResponse} carries, and identical for a reason: the
 * approved design puts one category axis across BOTH kinds so a single chip row filters the
 * whole screen. Derived from the LOCKED "salon offering = master-performed only" domain rule —
 * every distinct platform category of an active salon-owned service that at least one currently
 * ACTIVE master of this salon performs. A service the salon lists but no active master currently
 * performs contributes no chip, exactly as it contributes nothing to the public salon catalogue.
 *
 * <p>Resolved through the salon service feature's active-master offering definition — the same
 * predicate {@code MasterServiceRepository#findBookableAssignmentsBySalon} already uses to build
 * the public catalogue — so the favourites filter and the catalogue can never disagree about what
 * a salon "offers". See {@code FavoriteCategoryResolver}'s class javadoc for the full rationale.
 *
 * <p><b>Empty, never {@code null}.</b> A salon with no active master currently performing any
 * categorisable service publishes an empty list, not {@code null} — the client iterates directly.
 * This replaced an earlier {@code null}-when-client-never-booked-here contract; the axis no
 * longer reads this client's booking history at all, so it no longer varies per client. Both
 * fields — code and label — are always present together per entry; see
 * {@link FavoriteMasterResponse}'s javadoc for the full both-or-neither rationale, the batching
 * guarantee, and the recorded reversal of the earlier singular, booking-derived contract.
 *
 * @param categories every distinct platform category an active master of this salon performs an
 *                    active service in, ordered by display label; empty (never {@code null}) when
 *                    the salon has none
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
        List<FavoriteCategoryView> categories
) {
}
