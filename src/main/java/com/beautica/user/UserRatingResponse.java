package com.beautica.user;

import com.beautica.common.RatingBucket;

import java.math.BigDecimal;
import java.util.List;

/**
 * Response for {@code GET /users/me/rating} (Phase 27.6) — the caller's own aggregate rating as
 * reviewed by providers (via {@code client_reviews}), extended in Phase 27.x with a per-star
 * breakdown so the mobile «Мій рейтинг» screen can render the same rating-distribution table the
 * master/salon side already has ({@code MasterReviewSummaryResponse} /
 * {@code SalonReviewSummaryResponse}). AGGREGATE COUNTS ONLY — {@code avgRating}/{@code
 * reviewCount}/{@code ratingDistribution} — never a review list, comment text, reviewer identity,
 * or timestamps; a client sees only their own rating numbers, never provider comments about them
 * (locked product decision, mirrors the two-sided ratings pivot's client-side read contract).
 *
 * @param avgRating          {@code null} when the caller has never been reviewed.
 * @param reviewCount        total number of reviews received; {@code 0} when {@code avgRating} is
 *                           null.
 * @param ratingDistribution always exactly 5 entries (ratings 5 down to 1), zero-filled — a star
 *                           rating with no reviews still appears with {@code count = 0}. Uses the
 *                           shared {@link RatingBucket} record (in {@code common}) so this
 *                           response, {@code SalonReviewSummaryResponse}, and
 *                           {@code MasterReviewSummaryResponse} all resolve to one emitted OpenAPI
 *                           schema instead of three same-named-but-separate ones. The histogram is
 *                           intentionally coarser than a review list: it carries no reviewer
 *                           identity, no comment text, no timestamp, and no ordering beyond the
 *                           fixed 5-to-1 bucket layout, so it cannot be mapped back to any specific
 *                           booking or provider — never a review list or comment text.
 */
public record UserRatingResponse(BigDecimal avgRating, int reviewCount, List<RatingBucket> ratingDistribution) {

    public static UserRatingResponse from(UserRatingProjection projection, List<RatingBucket> ratingDistribution) {
        return new UserRatingResponse(projection.getAvgRating(), projection.getReviewCount(), ratingDistribution);
    }
}
