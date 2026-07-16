package com.beautica.booking.dto;

import com.beautica.booking.enums.CancellationReason;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Shared request body for {@code PATCH /decline} and {@code PATCH /not-complete}.
 *
 * <p>{@code comment} is OPTIONAL (Phase 25.9 — reverses the Phase 25.2 "required, ≥10 chars"
 * decision from earlier the same day: the product call is now that a provider may decline or
 * record a no-show without writing a note, for all roles). {@code null}/blank normalizes to
 * {@code null} via {@code BookingComments.normalize()} and persists cleanly (the V114
 * {@code chk_provider_comment_status} CHECK allows NULL regardless of status).
 */
public record StatusUpdateRequest(
        CancellationReason cancellationReason,
        // max=1000 mirrors booking.provider_comment VARCHAR(1000) (§A) — a raw-length ceiling
        // guarding against an oversized payload, independent of whether a comment is required.
        // Control-char ban prevents embedded NUL reaching the DB as a 500 instead of 400; line
        // breaks (\n, \r) and tabs (\t) are permitted — this is a long-form free-text field.
        @Size(max = 1000, message = "Comment must be at most 1000 characters")
        @Pattern(regexp = "^[^\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]*$",
                message = "Comment must not contain control characters other than line breaks and tabs")
        String comment
) {}
