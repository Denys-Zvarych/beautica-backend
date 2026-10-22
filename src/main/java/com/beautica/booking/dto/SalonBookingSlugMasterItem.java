package com.beautica.booking.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One bookable master on the public salon booking page ({@code GET /api/v1/book/s/{slug}/info}, phase 377).
 *
 * <p>Anti-Bug §I-2: a {@code permitAll} response — the master's public booking key ({@code masterSlug},
 * which drives the existing {@code /book/{slug}/*} flow), display name, avatar and title only. No master,
 * user or salon UUID, no contact data.
 */
public record SalonBookingSlugMasterItem(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "The master's public booking slug; use it with GET /api/v1/book/{slug}/info, "
                        + "/availability and /booking.")
        String masterSlug,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Composed \"First Last\".")
        String name,
        @Schema(types = {"string", "null"}, nullable = true)
        String avatarUrl,
        @Schema(types = {"string", "null"}, nullable = true)
        String professionalTitle,
        @Schema(types = {"number", "null"}, nullable = true,
                description = "The master's rating as the app's salon master card shows it; null while the master has "
                        + "no reviews (never a fabricated 0).")
        java.math.BigDecimal avgRating,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        int reviewCount
) {
}
