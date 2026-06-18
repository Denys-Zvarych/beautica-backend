package com.beautica.review.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Client-facing projection of a review the signed-in client has authored (Phase 19.4,
 * {@code GET /reviews/me}). Unlike the public {@link ReviewResponse} — which hides the client
 * identity — this shape exposes <em>which master and service</em> each review is about so the
 * client can recognise their own history.
 *
 * <p>Populated directly by a single constructor-expression JPQL query
 * ({@code ReviewRepository.findMyReviews}) joining {@code review.master.user} for the master
 * name and {@code review.booking.masterService.serviceDefinition} for the service name — no
 * N+1, no lazy traversal at mapping time.
 *
 * <p>This DTO is only ever returned to the authenticated author of the review, so {@code masterId}
 * and {@code bookingId} are not a public-ID leak (§I applies to {@code permitAll} endpoints only).
 *
 * @param createdAt review audit timestamp ({@code Instant}, ISO-8601 UTC on the wire) —
 *                  matches {@code AuditableEntity#getCreatedAt}; constructor expressions cannot
 *                  call {@code .atOffset(...)}, so the raw {@code Instant} is carried through.
 */
public record MyReviewResponse(
        UUID id,
        UUID masterId,
        String masterFirstName,
        String masterLastName,
        String serviceName,
        Integer rating,
        String comment,
        Instant createdAt,
        UUID bookingId
) {
}
