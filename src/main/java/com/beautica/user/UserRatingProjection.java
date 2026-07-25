package com.beautica.user;

import java.math.BigDecimal;

/**
 * Projection backing {@code GET /users/me/rating} (Phase 27.6) — the two aggregate columns ONLY.
 * Deliberately narrow (never the full {@link User} entity, which carries {@code passwordHash} and
 * other PII) — see {@code UserRepository#findRatingById}.
 */
public interface UserRatingProjection {
    BigDecimal getAvgRating();

    int getReviewCount();
}
