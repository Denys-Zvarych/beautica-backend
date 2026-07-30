package com.beautica.review.dto;

import com.beautica.common.RatingBucket;

import java.math.BigDecimal;
import java.util.List;

/**
 * Rating summary for a salon's public profile (Phase 13.6, {@code GET
 * /api/v1/salons/{salonId}/reviews/summary}).
 *
 * <p>{@code avgRating}/{@code reviewCount} are read from the persisted {@code salons.avg_rating}
 * / {@code salons.review_count} columns (via {@code SalonRepository.findById}) — no live
 * aggregation on this read path. Those columns are kept in sync on every review write by
 * {@code ReviewRepository#recalculateSalonRating}, so "recalculate on write, read persisted
 * on read" holds symmetrically with the master rating path.
 *
 * @param avgRating         {@code null} when {@code reviewCount} is 0 — never a fabricated
 *                           {@code 0.00} on a public DTO.
 * @param ratingDistribution always exactly 5 entries (ratings 5 down to 1), zero-filled —
 *                           a rating with no reviews still appears with {@code count = 0}.
 */
public record SalonReviewSummaryResponse(
        BigDecimal avgRating,
        int reviewCount,
        List<RatingBucket> ratingDistribution
) {
}
