package com.beautica.review.invite.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Phase 401 D3 — one rated service of a guest-link submit. Rating and comment carry EXACTLY the rules of the app's
 * {@code CreateReviewRequest} (1–5; comment optional, 1–2000 chars, starts with a visible character, no control
 * characters other than line breaks and tabs) — keep the two in step.
 *
 * @param bookingId a booking of the invite (anything else → 409)
 * @param rating    1–5 stars
 * @param comment   optional free text; {@code null} = no comment
 */
public record GuestReviewItemRequest(

        @NotNull(message = "Booking ID is required")
        UUID bookingId,

        @NotNull(message = "Rating is required")
        @Min(value = 1, message = "Rating must be between 1 and 5")
        @Max(value = 5, message = "Rating must be between 1 and 5")
        Integer rating,

        @Size(min = 1, max = 2000, message = "Comment must be between 1 and 2000 characters")
        @Pattern(
                regexp = "^[^\\p{Cntrl}\\s][^\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]{0,1999}$",
                message = "Comment must start with a visible character and must not contain "
                        + "control characters other than line breaks and tabs"
        )
        String comment

) {
}
