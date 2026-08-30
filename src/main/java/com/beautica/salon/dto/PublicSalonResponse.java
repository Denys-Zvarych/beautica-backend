package com.beautica.salon.dto;

import com.beautica.salon.entity.Salon;

import io.swagger.v3.oas.annotations.media.Schema;

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
 *
 * <p>{@code oblastId} was added as a follow-up to the {@code SalonResponse#oblastId} rollout:
 * {@code GET /salons/{salonId}} (unauthenticated, {@code permitAll}) is the ONLY load path the
 * mobile owner/admin salon-management screen actually uses, so leaving {@code oblastId} off this
 * DTO stranded the field the address-edit cascade needs. Not a disclosure concern (§I) — this
 * DTO already exposes {@code cityId}/{@code districtId} unmasked, and oblast is simply the
 * public, static parent tier of an already-public city in the government-territory taxonomy; see
 * {@code backend-security} audit note on commit {@code f00b6f1}. Like {@code SalonResponse}, it
 * is derived from {@code cityId} at read time (never stored) — callers pass the resolved value
 * in; see {@link #from(Salon, UUID)}.
 */
public record PublicSalonResponse(
        UUID id,
        String name,
        String description,
        String city,
        String region,
        String address,
        @Schema(
                format = "uuid",
                requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Taxonomy city. Every salon has one — salons.city_id is DB-level "
                        + "NOT NULL (V150/V151) and application-enforced from Phase 10.6 "
                        + "(LocalityWriteValidator). Never null on the wire.")
        UUID cityId,
        @Schema(
                format = "uuid",
                requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Parent oblast of cityId, resolved at read time (see #from). "
                        + "cities.oblast_id is itself DB-level NOT NULL with a FK to oblasts, "
                        + "and cityId is guaranteed non-null and FK-valid, so resolution always "
                        + "succeeds. Never null on the wire.")
        UUID oblastId,
        // Optional — a city without urban districts legitimately has none (§ locked decision).
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
    /**
     * @param salon    the salon entity
     * @param oblastId the PK of the Oblast that owns {@code salon.getCityId()}, resolved by
     *                 the caller (see {@code SalonService#resolveOblastId}). Never {@code null}
     *                 for a persisted salon — {@code cityId} is DB-level NOT NULL (V150/V151)
     *                 and FK-valid, and {@code cities.oblast_id} is itself NOT NULL with a FK
     *                 to {@code oblasts}, so the resolution always succeeds.
     */
    public static PublicSalonResponse from(Salon salon, UUID oblastId) {
        return new PublicSalonResponse(
                salon.getId(),
                salon.getName(),
                salon.getDescription(),
                salon.getCity(),
                salon.getRegion(),
                salon.getAddress(),
                salon.getCityId(),
                oblastId,
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
