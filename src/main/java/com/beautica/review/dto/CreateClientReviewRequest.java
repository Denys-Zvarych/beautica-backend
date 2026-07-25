package com.beautica.review.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/client-reviews} (Phase 27.5) — a provider rating the
 * CLIENT of a completed booking. {@code bookingId} identifies both the booking and (via its
 * graph) the client being reviewed; the actor (author) comes only from the authenticated
 * principal, never the body.
 */
public record CreateClientReviewRequest(

        @NotNull(message = "Booking ID is required")
        UUID bookingId,

        @NotNull(message = "Rating is required")
        @Min(value = 1, message = "Rating must be between 1 and 5")
        @Max(value = 5, message = "Rating must be between 1 and 5")
        Integer rating,

        // null means "no comment" — intentionally valid. max=2000 mirrors client_reviews.comment
        // VARCHAR(2000) (§A). Control-char ban prevents embedded NUL reaching the DB as a 500
        // instead of 400; line breaks (\n, \r) and tabs (\t) are permitted (mirrors
        // StatusUpdateRequest.comment's pattern — the provider's note here is likewise optional
        // free text, not required-and-non-blank like CreateReviewRequest.comment).
        @Size(max = 2000, message = "Comment must be at most 2000 characters")
        @Pattern(regexp = "^[^\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]*$",
                message = "Comment must not contain control characters other than line breaks and tabs")
        String comment

) {}
