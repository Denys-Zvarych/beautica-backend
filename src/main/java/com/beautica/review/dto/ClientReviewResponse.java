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
    public static ClientReviewResponse from(ClientReview review) {
        return new ClientReviewResponse(
                review.getId(),
                review.getBooking().getId(),
                review.getSubjectClient().getId(),
                review.getAuthorMaster().getId(),
                review.getRating().intValue(),
                review.getComment(),
                review.getCreatedAt().atOffset(ZoneOffset.UTC)
        );
    }
}
