package com.beautica.salon.dto;

import com.beautica.salon.entity.Salon;

import java.time.Instant;
import java.util.UUID;

public record SalonResponse(
        UUID id,
        UUID ownerId,
        String name,
        String description,
        String city,
        String region,
        String address,
        String phone,
        String instagramUrl,
        String avatarUrl,
        boolean isActive,
        Instant createdAt
) {
    public static SalonResponse from(Salon salon) {
        return new SalonResponse(
                salon.getId(),
                salon.getOwner() != null ? salon.getOwner().getId() : null,
                salon.getName(),
                salon.getDescription(),
                salon.getCity(),
                salon.getRegion(),
                salon.getAddress(),
                salon.getPhone(),
                salon.getInstagramUrl(),
                salon.getAvatarUrl(),
                salon.isActive(),
                salon.getCreatedAt()
        );
    }
}
