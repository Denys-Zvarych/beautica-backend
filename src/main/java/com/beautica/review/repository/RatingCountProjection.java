package com.beautica.review.repository;

/**
 * Projection interface for {@link ReviewRepository#countBySalonIdGroupByRating}.
 *
 * <p>Only ratings with at least one review are returned by the query — the service layer
 * ({@code ReviewService.getSalonReviewSummary}) zero-fills the missing 1-5 buckets.
 */
public interface RatingCountProjection {
    Integer getRating();
    Long getCount();
}
