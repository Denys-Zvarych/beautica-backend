package com.beautica.user;

import java.math.BigDecimal;

/**
 * Projection backing the {@code users.avg_rating} / {@code users.review_count} half of
 * {@code GET /users/me/rating} (Phase 27.6) — these two scalar columns ONLY. Deliberately narrow
 * (never the full {@link User} entity, which carries {@code passwordHash} and other PII) — see
 * {@code UserRepository#findRatingById}.
 *
 * <p>The response's per-star {@code ratingDistribution} (Phase 27.x) is sourced separately, via
 * {@code ClientReviewRepository#countBySubjectClientIdGroupByRating} — this projection does not
 * carry it and is not widened for it, since the distribution is a {@code GROUP BY} over
 * {@code client_reviews}, not a scalar column read off {@code users}.
 */
public interface UserRatingProjection {
    BigDecimal getAvgRating();

    int getReviewCount();
}
