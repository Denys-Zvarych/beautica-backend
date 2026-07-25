package com.beautica.user;

import java.math.BigDecimal;

/**
 * Response for {@code GET /users/me/rating} (Phase 27.6) — the caller's own aggregate rating as
 * reviewed by providers (via {@code client_reviews}). NUMBER ONLY — {@code avgRating}/{@code
 * reviewCount} — never a review list or any comment text; a client sees only their own rating
 * number, never provider comments about them (locked product decision, mirrors the two-sided
 * ratings pivot's client-side read contract).
 *
 * @param avgRating   {@code null} when the caller has never been reviewed.
 * @param reviewCount total number of reviews received; {@code 0} when {@code avgRating} is null.
 */
public record UserRatingResponse(BigDecimal avgRating, int reviewCount) {

    public static UserRatingResponse from(UserRatingProjection projection) {
        return new UserRatingResponse(projection.getAvgRating(), projection.getReviewCount());
    }
}
