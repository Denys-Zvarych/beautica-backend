package com.beautica.salon.dto;

import com.beautica.salon.entity.Salon;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Unauthenticated public view of a salon (returned by {@code GET /salons/{salonId}}).
 * Owner identifiers never appear on this {@code permitAll} path (§I).
 *
 * <p>Phase 10.6 bugfix: the taxonomy locality ({@code cityId} / {@code districtId}) and
 * light structured address ({@code street} / {@code buildingNo} / {@code locationNote}) are
 * now surfaced here too, mirroring {@link SalonResponse}. Without them this DTO exposed
 * nothing but the legacy free-text {@code city} / {@code region} / {@code address} fields,
 * which {@code SalonService} stopped writing as of Phase 10.6 — every salon created/edited
 * since then showed no location at all on its public profile. The legacy fields are kept on
 * the wire for backward-compatible clients. No name resolution is done here — like
 * {@code SalonResponse} and {@code MasterDetailResponse}, only raw taxonomy UUIDs are
 * returned; the client resolves human-readable names via {@code /locations/*}.
 *
 * <p>No masking is applied to the locality fields, unlike
 * {@link com.beautica.master.dto.MasterDetailResponse#fromPublic}, which masks a
 * salon-affiliated master's precise address because that address is the salon's own business
 * address duplicated onto the master. A salon's business address IS the thing salon
 * discovery exists to surface — masking it here would defeat the endpoint's purpose.
 */
public record PublicSalonResponse(
        UUID id,
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
                salon.getCityId(),
                salon.getDistrictId(),
                salon.getStreet(),
                salon.getBuildingNo(),
                salon.getLocationNote(),
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
