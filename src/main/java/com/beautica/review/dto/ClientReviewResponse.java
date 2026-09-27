package com.beautica.review.dto;

import com.beautica.review.entity.ClientReview;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Response for {@code POST /api/v1/client-reviews} (Phase 27.5). Authenticated, provider-only
 * endpoint (the caller is the review's own author), so including {@code authorMasterId} alongside
 * {@code clientId} is not a cross-tenant leak — this is not a public DTO.
 */
public record ClientReviewResponse(
        UUID id,
        UUID bookingId,
        UUID clientId,
        UUID authorMasterId,
        Integer rating,
        String comment,
        OffsetDateTime createdAt
) {

    // Callers must ensure booking, subjectClient, and authorMaster are loaded (JOIN FETCH) —
    // all are FetchType.LAZY. Mirrors ReviewResponse.from's fetched-graph contract.
    //
    // Defensive null-guard on subjectClient (Phase 300 §8): client_reviews rows are DELETED, not
    // detached, by ClientAccountDeletionService before the users row goes (D3), so a null
    // subjectClient should be unreachable in practice — this is cheap insurance against an
    // unguarded NPE, not a state this DTO is expected to actually render.
    public static ClientReviewResponse from(ClientReview review) {
        UUID subjectClientId = review.getSubjectClient() != null ? review.getSubjectClient().getId() : null;
        return new ClientReviewResponse(
                review.getId(),
                review.getBooking().getId(),
                subjectClientId,
                review.getAuthorMaster().getId(),
                review.getRating().intValue(),
                review.getComment(),
                review.getCreatedAt().atOffset(ZoneOffset.UTC)
        );
    }
}
