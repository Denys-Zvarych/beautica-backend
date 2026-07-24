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
        // Pattern: must START with a non-whitespace, non-control character (forbids
        // leading whitespace / blank-only comments). Subsequent characters may include
        // line breaks (\n, \r) and tabs (\t) — long-form comments legitimately contain
        // them — but still reject NUL/other C0 control chars and DEL (§D, §A).
        @Size(min = 1, max = 2000, message = "Comment must be between 1 and 2000 characters")
        @Pattern(
                regexp = "^[^\\p{Cntrl}\\s][^\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]{0,1999}$",
                message = "Comment must start with a visible character and must not contain "
                        + "control characters other than line breaks and tabs"
        )
        String comment

) {}
