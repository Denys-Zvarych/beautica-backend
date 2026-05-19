package com.beautica.salon.dto;

import com.beautica.salon.entity.Salon;

import java.time.Instant;
import java.util.UUID;

/**
 * Authenticated owner/admin view of a salon (returned by
 * {@code POST/PATCH /salons}, {@code GET /salons/mine}). The unauthenticated
 * public view is the separate {@code PublicSalonResponse} — owner identifiers
 * never appear on the {@code permitAll} path (§I).
 *
 * <p>Phase 10.6: the taxonomy locality ({@code cityId} / {@code districtId})
 * and light structured address ({@code street} / {@code buildingNo} /
 * {@code locationNote}) are surfaced so the caller can read back what it just
 * wrote. The legacy free-text {@code city} / {@code region} / {@code address}
 * are retained on the wire (now always whatever was last persisted before
 * Phase 10.6 — no longer written) for backward-compatible clients.
 */
public record SalonResponse(
        UUID id,
        UUID ownerId,
        String name,
        String description,
        String city,
        String region,
        String address,
        UUID cityId,
        UUID districtId,
        String street,
        String buildingNo,
        String locationNote,
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
                salon.getCityId(),
                salon.getDistrictId(),
                salon.getStreet(),
                salon.getBuildingNo(),
                salon.getLocationNote(),
                salon.getPhone(),
                salon.getInstagramUrl(),
                salon.getAvatarUrl(),
                salon.isActive(),
                salon.getCreatedAt()
        );
    }
}
