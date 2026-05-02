package com.beautica.salon.dto;

import com.beautica.salon.entity.Salon;

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
        boolean isActive
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
                salon.isActive()
        );
    }
}
