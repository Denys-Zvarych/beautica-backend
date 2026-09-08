package com.beautica.review.dto;

import com.beautica.review.entity.Review;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

public record ReviewResponse(

        UUID id,
        UUID masterId,
        String clientDisplayName,
        String serviceName,
        Integer rating,
        String comment,
        OffsetDateTime createdAt

) {

    /**
     * Neutral author label rendered in place of a self-deleted client's real name (Phase 300 D3).
     * {@code reviews.client_id} became nullable so the review itself — rating and comment — survives
     * a {@code DELETE /users/me} hard delete with the provider's {@code avgRating}/{@code
     * reviewCount} untouched; only the authorship is severed, and this label stands in for it. The
     * sentinel is deliberately the SAME literal {@code ClientAccountDeletionService} writes to a
     * detached booking's {@code guest_name} — one consistent "this person deleted their account"
     * signal across the app — but is declared as its own constant here rather than shared across
     * packages, mirroring how {@code Master.DETACHED_FALLBACK_FIRST_NAME} and {@code
     * SalonReviewResponse.DETACHED_MASTER_LABEL} are independently-declared constants that happen
     * to agree on the same Ukrainian noun.
     */
    public static final String DETACHED_CLIENT_LABEL = "Видалений клієнт";

    // Callers must ensure master, client, and booking.masterService.serviceDefinition are
    // loaded (JOIN FETCH / @EntityGraph) — all are FetchType.LAZY. review.booking is NOT NULL
    // (unique FK, Review entity) and Booking.masterService is NOT NULL, so the chain below never
    // needs a null guard — same contract SalonReviewResponse.from relies on. review.client CAN be
    // null since Phase 300 (a self-deleted author) — guarded below.
    public static ReviewResponse from(Review review) {
        String displayName;
        if (review.getClient() == null) {
            displayName = DETACHED_CLIENT_LABEL;
        } else {
            String firstName = review.getClient().getFirstName();
            String lastName = review.getClient().getLastName();
            if (firstName == null && lastName == null) {
                displayName = "Anonymous";
            } else if (firstName != null && lastName != null) {
                displayName = firstName + " " + lastName.charAt(0) + ".";
            } else if (firstName != null) {
                displayName = firstName;
            } else {
                displayName = lastName.charAt(0) + ".";
            }
        }
        return new ReviewResponse(
                review.getId(),
                review.getMaster().getId(),
                displayName,
                review.getBooking().getMasterService().getServiceDefinition().getName(),
                review.getRating().intValue(),
                review.getComment(),
                review.getCreatedAt().atOffset(ZoneOffset.UTC)
        );
    }
}
