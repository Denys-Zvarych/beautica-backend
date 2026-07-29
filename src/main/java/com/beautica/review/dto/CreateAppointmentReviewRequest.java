package com.beautica.review.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for a multi-service visit review (BE-6) — {@code POST
 * /api/v1/appointments/{appointmentId}/review}. The appointment id is the path variable, so this
 * body carries ONLY the rating + optional comment; the {@code rating}/{@code comment} constraints
 * are identical to {@link CreateReviewRequest} (the legacy single-booking body), which is why there
 * is no {@code bookingId} here.
 */
public record CreateAppointmentReviewRequest(

        @NotNull(message = "Rating is required")
        @Min(value = 1, message = "Rating must be between 1 and 5")
        @Max(value = 5, message = "Rating must be between 1 and 5")
        Integer rating,

        // null means "no comment" — intentionally valid; do NOT add @NotBlank here.
        // Same constraint set as CreateReviewRequest.comment: @Size(min=1) rejects the empty string
        // while allowing null; the @Pattern forbids leading whitespace / blank-only comments and
        // control chars other than line breaks and tabs (§A, §D).
        @Size(min = 1, max = 2000, message = "Comment must be between 1 and 2000 characters")
        @Pattern(
                regexp = "^[^\\p{Cntrl}\\s][^\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]{0,1999}$",
                message = "Comment must start with a visible character and must not contain "
                        + "control characters other than line breaks and tabs"
        )
        String comment

) {}
