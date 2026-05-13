package com.beautica.review.dto;

import com.beautica.review.entity.Review;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

public record ReviewResponse(

        UUID id,
        UUID masterId,
        String clientDisplayName,
        Integer rating,
        String comment,
        OffsetDateTime createdAt

) {

    // Callers must ensure master and client are loaded (JOIN FETCH / @EntityGraph) — both are FetchType.LAZY.
    public static ReviewResponse from(Review review) {
        String firstName  = review.getClient().getFirstName();
        String lastName   = review.getClient().getLastName();
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
        return new ReviewResponse(
                review.getId(),
                review.getMaster().getId(),
                displayName,
                review.getRating().intValue(),
                review.getComment(),
                review.getCreatedAt().atOffset(ZoneOffset.UTC)
        );
    }
}
