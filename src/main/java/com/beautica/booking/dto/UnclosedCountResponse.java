package com.beautica.booking.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response shape for {@code GET /bookings/me/unclosed-count} (Phase 29.4) — the provider
 * work-queue badge count. {@code count} is exactly {@code totalElements} of {@code GET
 * /bookings/me?partition=AWAITING_CLOSURE} for the same authenticated principal at the same
 * instant; see {@code BookingService#getUnclosedCount}. Never cached — the count is a pure
 * function of the current instant and would go stale the moment a booking's {@code endsAt}
 * passes.
 */
public record UnclosedCountResponse(
        @Schema(description = "Non-negative count of the caller's own bookings currently "
                + "awaiting closure (CONFIRMED and elapsed). Scope mirrors GET /bookings/me's "
                + "provider/client scope exactly — see that endpoint's role table.")
        long count
) {
}
