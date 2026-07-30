package com.beautica.common;

/**
 * One star-rating histogram bucket, shared by every {@code ratingDistribution} response across
 * features: {@code SalonReviewSummaryResponse}, {@code MasterReviewSummaryResponse}
 * ({@code review.dto}), and {@code UserRatingResponse} ({@code user}).
 *
 * <p>This type MUST stay a single top-level class rather than a per-feature nested record.
 * Springdoc keys OpenAPI component schemas by simple name — if two unrelated records were both
 * named {@code RatingBucket}, springdoc would silently merge them into one emitted
 * {@code RatingBucket} schema. Today the shapes are identical so the merge would be harmless, but
 * the moment either response's bucket needs to diverge (e.g. an added field), the merged schema
 * would mask the divergence with no compile error and no test failure — one of the two contracts
 * would silently go wrong in the generated mobile Dart client. Sharing this one type makes that
 * class of bug structurally impossible: there is only one {@code RatingBucket} to diverge from.
 *
 * <p>Lives in {@code common} (not {@code review.dto}) so that {@code user} does not take on a
 * compile-time dependency on the {@code review} feature package for response shaping.
 *
 * @param rating the star rating this bucket counts, 1 through 5
 * @param count  number of reviews with this rating; {@code 0} is a valid, expected value —
 *               callers zero-fill every bucket from 5 down to 1 rather than omitting empty ones
 */
public record RatingBucket(int rating, long count) {
}
