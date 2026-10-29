package com.beautica.review.invite.dto;

/**
 * Phase 401 — {@code 201} body of a guest-link submit.
 *
 * @param created the number of reviews written (= the submitted items)
 */
public record GuestReviewSubmitResponse(int created) {
}
