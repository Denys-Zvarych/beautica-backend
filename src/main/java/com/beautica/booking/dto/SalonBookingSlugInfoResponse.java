package com.beautica.booking.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Public payload for {@code GET /api/v1/book/s/{slug}/info} — the website's salon booking page (phase 377).
 *
 * <p>Anti-Bug §I-2: a {@code permitAll} response. {@code salonId} is the salon's PUBLIC identifier (the
 * same one {@code GET /salons/{salonId}} is keyed by, D7); no owner id, owner/admin contact data or
 * internal flag appears. {@code masters} lists only bookable masters (locked client-visibility rule);
 * {@code bookable} is {@code false} exactly when that list is empty (D3).
 */
public record SalonBookingSlugInfoResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid")
        UUID salonId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String name,
        @Schema(types = {"string", "null"}, nullable = true,
                description = "Settlement display name (salons.city, denormalised from the taxonomy city).")
        String city,
        @Schema(types = {"string", "null"}, nullable = true)
        String avatarUrl,
        @Schema(types = {"string", "null"}, nullable = true)
        String coverImageUrl,
        @Schema(types = {"number", "null"}, nullable = true,
                description = "null while the salon has no reviews (never a fabricated 0).")
        BigDecimal avgRating,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        int reviewCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "false when no master of the salon is bookable yet; masters is then empty.")
        boolean bookable,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        List<SalonBookingSlugMasterItem> masters,
        @Schema(types = {"string", "null"}, nullable = true,
                description = "Settlement region label (salons.region).")
        String region,
        @Schema(types = {"string", "null"}, nullable = true,
                description = "Street; falls back to the legacy free-text address when street is blank.")
        String street,
        @Schema(types = {"string", "null"}, nullable = true)
        String buildingNo,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Salon last edited; display/JSON-LD only, never a cache validator.")
        Instant updatedAt
) {
}
