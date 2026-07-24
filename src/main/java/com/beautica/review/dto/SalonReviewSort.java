package com.beautica.review.dto;

/**
 * Fixed sort options for {@code GET /api/v1/salons/{salonId}/reviews} (Phase 13.6).
 *
 * <p>Deliberately a closed enum rather than a caller-supplied Spring {@code Sort} —
 * accepting a free-form sort string would let a caller probe entity property names via
 * {@code PropertyReferenceException} (the same reasoning documented on
 * {@code ReviewRepository#findIdsByMasterIdOrderByCreatedAtDesc}). Each value maps to one
 * fixed, named repository method in {@code ReviewService.getSalonReviews}.
 */
public enum SalonReviewSort {
    NEWEST,
    OLDEST,
    HIGHEST,
    LOWEST
}
