package com.beautica.common;

import java.util.UUID;

/**
 * Projection of an ACTIVE reviewable entity (master or salon) behind a public booking slug: its id and the persisted
 * {@code review_count}. Lets the public {@code /reviews} routes answer a page beyond the last one WITHOUT touching the
 * review cache (phase 383/384 audit) - see {@code BookingSlugService#reviewsForSlug}.
 */
public interface ReviewTarget {

    UUID getId();

    int getReviewCount();
}
