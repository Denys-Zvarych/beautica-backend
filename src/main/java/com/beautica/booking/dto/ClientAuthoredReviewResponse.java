package com.beautica.booking.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The CLIENT-authored review of the provider, as it hangs off ONE booking (Phase 317).
 *
 * <p><b>Read the direction carefully — this is not the {@code ClientReview} family.</b> The
 * artifact carried here is a {@link com.beautica.review.entity.Review}: written BY the client
 * ABOUT the master, the one {@code BookingDetailResponse#canReview} gates the creation of, and the
 * one already served publicly by {@code GET /masters/&#123;id&#125;/reviews}. Its mirror image —
 * {@link com.beautica.review.entity.ClientReview}, written BY the provider ABOUT the client and
 * gated by {@code providerCanReviewClient} — is a different entity, a different endpoint
 * ({@code POST /client-reviews}) and is NOT exposed by this record. The two are one letter apart in
 * the codebase's vocabulary and opposite in meaning; the field that carries this record is named
 * {@code reviewByClient} rather than {@code clientReview} for exactly that reason.
 *
 * <p><b>Phase 317 D2 (locked) — the FULL comment ships, not the rating alone.</b> Withholding the
 * text would be theatre: the same review, verbatim, is already readable by anyone at all through
 * the {@code permitAll} {@code GET /masters/&#123;id&#125;/reviews} listing. What that public
 * listing does NOT offer is the per-booking join — it carries no {@code bookingId}, deliberately
 * (Anti-Bug §I-2: a {@code permitAll} response must not hand out internal ids) — which is the gap
 * this record closes for the authenticated provider looking at one booking.
 *
 * <p>Carries NO author identity. The reviewer is, by construction, the booking's own client, whose
 * name and photo the enclosing {@code BookingDetailResponse} already renders; repeating it here
 * would be a second, independently-drifting copy of the same PII.
 *
 * @param rating the client's 1-5 star rating, widened from the entity's {@code Short} exactly as
 *               {@code ReviewResponse#from} widens it, so the two surfaces agree on the wire type
 * @param comment the client's free text, or {@code null} — a rating may be left without one
 */
@Schema(description = "The review this booking's CLIENT left about the master (rating + full "
        + "comment). Null when no review exists for the booking. NOT the provider's review of the "
        + "client — that is a separate entity, written through POST /client-reviews and gated by "
        + "providerCanReviewClient.")
public record ClientAuthoredReviewResponse(

        @Schema(description = "The client's star rating for this booking, 1-5.")
        Integer rating,

        @Schema(types = {"string", "null"}, nullable = true,
                description = "The client's review text, verbatim and unabridged, or null when "
                        + "they rated without writing anything. Already public via "
                        + "GET /masters/{id}/reviews — never truncated or masked here.")
        String comment

) {}
