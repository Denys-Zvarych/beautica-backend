package com.beautica.review.dto;

import com.beautica.review.entity.Review;
import com.beautica.review.entity.ReviewSource;
import io.swagger.v3.oas.annotations.media.Schema;

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
        OffsetDateTime createdAt,

        @Schema(description = "True when the authenticated caller wrote this review (the app hides «Поскаржитися» "
                + "on it); always false for an anonymous caller and on booking-link (slug) pages.")
        boolean authoredByViewer,

        @Schema(description = "Who wrote the review (phase 399): APP = a client account in the app; GUEST_LINK = a "
                + "booking-link guest through an SMS invite, clientDisplayName is then the guest's «Ім'я П.» snapshot.")
        ReviewSource source

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
    // null since Phase 300 (a self-deleted author) and on every GUEST_LINK review (phase 399) —
    // ReviewAuthorLabel resolves the author name for all three shapes.
    /**
     * Viewer-neutral mapping: {@code authoredByViewer = false}. This is the shape that is CACHED
     * ({@code reviews-by-master}, {@code review-detail}), so it must never carry a per-viewer value;
     * {@code ReviewViewerService} stamps the flag per request on the way out (phase 395).
     */
    public static ReviewResponse from(Review review) {
        String displayName = ReviewAuthorLabel.of(review);
        return new ReviewResponse(
                review.getId(),
                review.getMaster().getId(),
                displayName,
                review.getBooking().getMasterService().getServiceDefinition().getName(),
                review.getRating().intValue(),
                review.getComment(),
                review.getCreatedAt().atOffset(ZoneOffset.UTC),
                false,
                review.getSource()
        );
    }

    /** A copy with {@code authoredByViewer} set — never mutate a cached instance. */
    public ReviewResponse withAuthoredByViewer(boolean authored) {
        return authored == authoredByViewer ? this
                : new ReviewResponse(id, masterId, clientDisplayName, serviceName, rating, comment, createdAt, authored,
                        source);
    }
}
