package com.beautica.booking.dto;

import com.beautica.common.RatingBucket;
import com.beautica.master.entity.MasterType;
import com.beautica.media.dto.MediaFileResponse;
import com.beautica.review.dto.ReviewResponse;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Public payload for {@code GET /api/v1/book/{slug}/info} — the unauthenticated
 * guest booking page (Phase 13.1), enriched in phase 380 for the website's SSR/SEO page.
 *
 * <p>Anti-Bug §I-2 / phase 380 D4: a {@code permitAll} response. Every field is already public on
 * another anonymous endpoint; no phone, email, instagram, street, coordinates or client identity
 * beyond {@link ReviewResponse#clientDisplayName()}. {@code masterName} is the composed "First Last".
 * The first four components keep name, type and order (D1); the rest are appended.
 */
public record BookingSlugInfoResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String masterName,
        @Schema(types = {"string", "null"}, nullable = true)
        String avatarUrl,
        @Schema(types = {"string", "null"}, nullable = true)
        String bio,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        List<ServiceSummaryDto> services,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid")
        UUID masterId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        MasterType masterType,
        @Schema(types = {"string", "null"}, nullable = true)
        String professionalTitle,
        @Schema(types = {"string", "null"}, nullable = true)
        String city,
        @Schema(types = {"string", "null"}, nullable = true)
        String region,
        @Schema(types = {"object", "null"}, nullable = true,
                description = "null for independent masters.")
        BookingSlugSalonRef salon,
        @Schema(types = {"number", "null"}, nullable = true,
                description = "null while the master has no reviews (never a fabricated 0).")
        BigDecimal avgRating,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        int reviewCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        List<CategoryRef> categories,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        List<MediaFileResponse> portfolio,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        List<ReviewResponse> latestReviews,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "false when the master has no active service or no working schedule yet; "
                        + "the page then renders «not accepting online bookings».")
        boolean bookable,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Profile last edited; display/JSON-LD only, never a cache validator.")
        Instant updatedAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Zero-filled 5..1 histogram, the same as GET /masters/{id}/reviews/summary (phase 383).")
        List<RatingBucket> ratingDistribution,
        @Schema(types = {"string", "null"}, nullable = true,
                description = "Non-null only for INDEPENDENT_MASTER (the app's public-profile address rule).")
        String street,
        @Schema(types = {"string", "null"}, nullable = true,
                description = "Non-null only for INDEPENDENT_MASTER.")
        String buildingNo,
        @Schema(types = {"string", "null"}, nullable = true,
                description = "Non-null only for INDEPENDENT_MASTER.")
        String locationNote
) {
}
