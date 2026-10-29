package com.beautica.review.service;

import com.beautica.booking.entity.Booking;

import java.util.Objects;

/**
 * Phase 401 — one guest-link review to write through {@link ReviewService#createGuestLinkReviews}: the invite's own
 * (already resolved, managed) booking plus the guest's rating and optional comment, both validated at the controller
 * boundary with the same rules as {@code CreateReviewRequest}.
 */
public record GuestLinkReviewDraft(Booking booking, int rating, String comment) {

    public GuestLinkReviewDraft {
        Objects.requireNonNull(booking, "booking");
    }
}
