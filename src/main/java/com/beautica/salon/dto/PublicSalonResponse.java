package com.beautica.salon.dto;

import com.beautica.salon.entity.Salon;

import java.math.BigDecimal;
import java.util.UUID;

public record PublicSalonResponse(
        UUID id,
        String name,
        String description,
        String city,
        String region,
        String address,
        String instagramUrl,
        String avatarUrl,
        String coverImageUrl,
        BigDecimal avgRating,
        int reviewCount
) {
    public static PublicSalonResponse from(Salon salon) {
        return new PublicSalonResponse(
                salon.getId(),
                salon.getName(),
                salon.getDescription(),
                salon.getCity(),
                salon.getRegion(),
                salon.getAddress(),
                salon.getInstagramUrl(),
                salon.getAvatarUrl(),
                salon.getCoverImageUrl(),
                // A salon with zero reviews has no meaningful average — null, not 0 —
                // regardless of what happens to be persisted in the column (anti-bug §A/§I
                // spirit: never surface a fabricated "0.00" rating on a public DTO).
                salon.getReviewCount() == 0 ? null : salon.getAvgRating(),
                salon.getReviewCount()
        );
    }
}
