package com.beautica.review.dto;

import com.beautica.review.entity.Review;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

public record ReviewResponse(

        UUID id,
        UUID bookingId,
        UUID masterId,
        String clientDisplayName,
        Integer rating,
        String comment,
        OffsetDateTime createdAt

) {

    // Callers must ensure booking, master, and client are loaded (JOIN FETCH / @EntityGraph) — all three are FetchType.LAZY.
    public static ReviewResponse from(Review review) {
        String firstName  = review.getClient().getFirstName();
        String lastName   = review.getClient().getLastName();
        String displayName;
        if (firstName == null && lastName == null) {
            displayName = "Anonymous";
        } else {
            displayName = (firstName != null ? firstName : "")
                    + (firstName != null && lastName != null ? " " : "")
                    + (lastName != null ? lastName : "");
        }
        return new ReviewResponse(
                review.getId(),
                review.getBooking().getId(),
                review.getMaster().getId(),
                displayName,
                review.getRating().intValue(),
                review.getComment(),
                review.getCreatedAt().atOffset(ZoneOffset.UTC)
        );
    }
}
