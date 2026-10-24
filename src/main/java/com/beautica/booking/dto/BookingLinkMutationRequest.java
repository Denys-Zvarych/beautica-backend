package com.beautica.booking.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Phase 378 D7 — body of the four regenerate / revoke routes: the slug the caller is looking at, for an
 * optimistic compare-and-swap. A stale value (the link changed since the app last read it — a double tap,
 * or owner and admin acting at once) → 409 and nothing changes.
 *
 * @param currentSlug the link's current slug, or {@code null} when the app shows it as REVOKED. Same shape
 *                    as the V86 / V200 slug CHECK, at most 60 chars (the column length).
 */
@Schema(description = "Optimistic concurrency token for a booking-link regenerate / revoke")
public record BookingLinkMutationRequest(
        @Schema(types = {"string", "null"}, nullable = true, maxLength = 60, example = "olena-koval-x7k2",
                description = "The slug the app currently shows; null when the link is REVOKED. "
                        + "Mismatch with the stored slug → 409")
        @Size(max = 60)
        @Pattern(regexp = "^[a-z0-9][a-z0-9-]*[a-z0-9]$")
        String currentSlug
) {
}
