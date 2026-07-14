package com.beautica.booking.dto;

import com.beautica.booking.enums.CancellationReason;
import com.beautica.booking.validation.NormalizedSize;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Shared request body for {@code PATCH /decline} and {@code PATCH /not-complete}.
 *
 * <p>{@code comment} is {@code @NotBlank} + a 10-char floor (Phase 25.2, locked product
 * decision — "all notes should be visible for all sides"): the provider owes the client a real
 * explanation when backing out of a confirmed booking or recording a no-show, and this note is
 * shown directly to the client (email + {@code GET /bookings/me}), so a one-character note would
 * defeat the whole premise. min=10 is intentionally UI-adjacent (not a security boundary) —
 * it just rejects the degenerate "" / "." / "ok" cases; genuinely short-but-meaningful text
 * ("No show") does not clear it and the caller is expected to write one sentence.
 *
 * <p>The 10-char floor is enforced on the NORMALIZED string ({@link NormalizedSize}), not the
 * raw one (LOW finding): a plain {@code @Size(min = 10)} runs before
 * {@code BookingComments.normalize()} strips zero-width/bidi/other Unicode {@code Cf} padding in
 * the service layer, so {@code "ok"} + 8 zero-width spaces (raw length 10) would otherwise clear
 * the floor and then normalize back down to the 2-character degenerate case this floor exists to
 * reject.
 */
public record StatusUpdateRequest(
        CancellationReason cancellationReason,
        // max=1000 mirrors booking.provider_comment VARCHAR(1000) (§A) — a raw-length ceiling
        // guarding against an oversized payload before normalization even runs. Control-char ban
        // prevents embedded NUL reaching the DB as a 500 instead of 400; line breaks
        // (\n, \r) and tabs (\t) are permitted — this is a long-form free-text field. The min
        // floor lives on @NormalizedSize below, not here — see the class javadoc.
        @NotBlank(message = "A comment is required")
        @Size(max = 1000, message = "Comment must be at most 1000 characters")
        @Pattern(regexp = "^[^\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]*$",
                message = "Comment must not contain control characters other than line breaks and tabs")
        @NormalizedSize(min = 10, max = 1000,
                message = "Comment must be between 10 and 1000 characters after normalization")
        String comment
) {}
