package com.beautica.review.dto;

import com.beautica.review.entity.Review;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Single review row for a salon's public review list (Phase 13.6, {@code GET
 * /api/v1/salons/{salonId}/reviews}).
 *
 * <p>Unlike the master-scoped {@link ReviewResponse}, this shape also names the reviewed
 * master ({@code masterId}/{@code masterFirstName}/{@code masterLastName}) — a salon has
 * multiple masters, so the client needs to know which one each review is about — and the
 * booked service name.
 *
 * <p>Callers must load {@code review.client}, {@code review.master.user},
 * {@code review.booking.masterService.serviceDefinition} eagerly (see
 * {@code ReviewRepository#findByIdsWithGraphForSalonReviews}) — all four associations are
 * {@code FetchType.LAZY} on the entity.
 */
public record SalonReviewResponse(
        UUID id,
        UUID masterId,
        String masterFirstName,
        String masterLastName,
        String clientDisplayName,
        String serviceName,
        Integer rating,
        String comment,
        OffsetDateTime createdAt
) {
    // Client-name masking logic mirrors ReviewResponse.from exactly — same
    // "FirstName L." / "FirstName" / "L." / "Anonymous" rules.
    public static SalonReviewResponse from(Review review) {
        String firstName = review.getClient().getFirstName();
        String lastName = review.getClient().getLastName();
        String displayName;
        if (firstName == null && lastName == null) {
            displayName = "Anonymous";
        } else if (firstName != null && lastName != null) {
            displayName = firstName + " " + lastName.charAt(0) + ".";
        } else if (firstName != null) {
            displayName = firstName;
        } else {
            displayName = lastName.charAt(0) + ".";
        }
        return new SalonReviewResponse(
                review.getId(),
                review.getMaster().getId(),
                review.getMaster().getUser().getFirstName(),
                review.getMaster().getUser().getLastName(),
                displayName,
                review.getBooking().getMasterService().getServiceDefinition().getName(),
                review.getRating().intValue(),
                review.getComment(),
                review.getCreatedAt().atOffset(ZoneOffset.UTC)
        );
    }
}
