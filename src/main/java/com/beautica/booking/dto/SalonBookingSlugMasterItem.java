package com.beautica.booking.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One bookable master on the public salon booking page ({@code GET /api/v1/book/s/{slug}/info}, phase 377).
 *
 * <p>Anti-Bug §I-2: a {@code permitAll} response — the master's public id ({@code masterId}, phase 391: the
 * {@code {masterId}} of the salon guest routes {@code /book/s/{slug}/masters/{masterId}/*}, already public via
 * {@code GET /salons/{id}/masters}), the optional personal booking slug, display name, avatar and title only.
 * No user or salon UUID, no contact data.
 */
public record SalonBookingSlugMasterItem(
        @Schema(types = {"string", "null"}, nullable = true,
                description = "The master's personal booking slug (GET /api/v1/book/{slug}/info); null when the "
                        + "master has no live personal link (phase 391) — book them through the salon routes "
                        + "/api/v1/book/s/{slug}/masters/{masterId}/*.")
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
        int reviewCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid",
                description = "Phase 391 — the master's id: the {masterId} of /api/v1/book/s/{slug}/masters/{masterId}/* "
                        + "and the key serviceCoverage[].masters[].masterId refers to.")
        java.util.UUID masterId
) {
}
