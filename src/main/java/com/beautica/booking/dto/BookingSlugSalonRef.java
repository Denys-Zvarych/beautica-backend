package com.beautica.booking.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The salon a master belongs to, on the public master booking page (phase 380 D2). {@code bookingSlug}
 * is {@code null} until the salon's slug is lazily issued (phase 376 D4).
 */
public record BookingSlugSalonRef(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String name,
        @Schema(types = {"string", "null"}, nullable = true)
        String bookingSlug
) {
}
