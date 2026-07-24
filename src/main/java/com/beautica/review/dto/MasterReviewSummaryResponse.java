package com.beautica.review.dto;

import com.beautica.review.dto.SalonReviewSummaryResponse.RatingBucket;

import java.math.BigDecimal;
import java.util.List;

/**
 * Rating summary for a master's public profile (Phase 8.10, {@code GET
 * /api/v1/masters/{masterId}/reviews/summary}).
 *
 * <p>Structural clone of {@link SalonReviewSummaryResponse} — deliberately a separate record
 * rather than a shared/renamed type so the shipped salon OpenAPI contract and mobile
 * {@code SalonReviewMapper} are untouched (Phase 8.10 decision 1). It reuses the existing
 * {@link RatingBucket} record instead of declaring its own, so the two summary DTOs share one
 * bucket shape.
 *
 * <p>{@code avgRating}/{@code reviewCount} are read from the persisted {@code masters.avg_rating}
 * / {@code masters.review_count} columns (via {@code MasterRepository.findById}) — no live
 * aggregation on this read path. Those columns are kept in sync on every review write by
 * {@code ReviewRepository#recalculateMasterRating}, so "recalculate on write, read persisted
 * on read" holds symmetrically with the salon rating path.
 *
 * @param avgRating          {@code null} when {@code reviewCount} is 0 — never a fabricated
 *                           {@code 0.00} on a public DTO.
 * @param reviewCount        total number of reviews the master has received.
 * @param ratingDistribution always exactly 5 entries (ratings 5 down to 1), zero-filled —
 *                           a rating with no reviews still appears with {@code count = 0}.
 */
public record MasterReviewSummaryResponse(
        BigDecimal avgRating,
        int reviewCount,
        List<RatingBucket> ratingDistribution
) {
}
