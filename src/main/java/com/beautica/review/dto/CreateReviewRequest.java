package com.beautica.review.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateReviewRequest(

        @NotNull(message = "Booking ID is required")
        UUID bookingId,

        @NotNull(message = "Rating is required")
        @Min(value = 1, message = "Rating must be between 1 and 5")
        @Max(value = 5, message = "Rating must be between 1 and 5")
        Integer rating,

        // null means "no comment" — intentionally valid; do NOT add @NotBlank here.
        // @Size(min=1) rejects empty string while allowing null.
        // Pattern: must start with a non-whitespace, non-control character, and contain
        // no control characters anywhere (§D: control-char ban on all free-text DB fields).
        @Size(min = 1, max = 2000, message = "Comment must be between 1 and 2000 characters")
        @Pattern(
                regexp = "^[^\\p{Cntrl}\\s][^\\p{Cntrl}]{0,1999}$",
                message = "Comment must start with a visible character and must not contain control characters"
        )
        String comment

) {}
