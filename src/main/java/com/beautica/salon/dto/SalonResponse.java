package com.beautica.salon.dto;

import com.beautica.location.SettlementDisplayNames;
import com.beautica.location.entity.SettlementType;
import com.beautica.salon.entity.Salon;

import io.swagger.v3.oas.annotations.media.Schema;

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
 *
 * <p>{@code oblastId} (added alongside the {@code SalonAddressEditScreen} work) lets the
 * mobile salon address-edit screen pre-select the oblast tier of the oblast→city→district
 * cascade directly, instead of scanning every oblast's city list — mirroring
 * {@code UserProfileResponse#oblastId} and {@code MasterDetailResponse#oblastId}. It is
 * derived from {@code cityId} at read time (never stored), so callers must pass the
 * resolved value in; see {@link #from(Salon, UUID, SettlementDisplayNames)}.
 */
public record SalonResponse(
        UUID id,
        UUID ownerId,
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
        String phone,
        String instagramUrl,
        String avatarUrl,
        @Schema(
                types = {"string", "null"},
                nullable = true,
                description = "Salon cover (banner) image URL, 16:9. Set only by the salon's "
                        + "SALON_OWNER via POST /salons/{salonId}/media/cover (Phase 343); null "
                        + "when unset.")
        String coverImageUrl,
        boolean isActive,
        // Phase 12.1: isPrimary surfaces the DB-level one-primary-per-owner invariant
        // so callers can determine whether the salon created during registration is primary.
        boolean isPrimary,
        Instant createdAt,
        @Schema(
                types = {"string", "null"},
                nullable = true,
                description = "Kind of the settlement behind cityId, so the client can prefix "
                        + "the saved-locality label (м./смт/с./с-ще) exactly as for a "
                        + "GET /settlements row. Resolved at read time; null only if cityId "
                        + "does not resolve.")
        SettlementType citySettlementType,
        @Schema(
                types = {"string", "null"},
                nullable = true,
                description = "Bare hromada adjective of the settlement behind cityId, populated "
                        + "ONLY when its name is ambiguous within its oblast (same rule as "
                        + "GET /settlements hromadaNameUk); null otherwise. The oblast half of "
                        + "the label is `region`.")
        String cityHromadaNameUk
) {
    /**
     * @param salon    the salon entity
     * @param oblastId the PK of the Oblast that owns {@code salon.getCityId()}, resolved by
     *                 the caller (see {@code SalonService#resolveOblastId}). Never {@code null}
     *                 for a persisted salon — {@code cityId} is DB-level NOT NULL (V150/V151)
     *                 and FK-valid, and {@code cities.oblast_id} is itself NOT NULL with a FK
     *                 to {@code oblasts}, so the resolution always succeeds.
     * @param settlement resolved label parts of {@code salon.getCityId()} (see
     *                   {@code SettlementDisplayNameResolver}), or {@code null} when unresolved —
     *                   supplies {@code citySettlementType} and {@code cityHromadaNameUk}
     */
    public static SalonResponse from(
            Salon salon, UUID oblastId, SettlementDisplayNames settlement) {
        return new SalonResponse(
                salon.getId(),
                salon.getOwner() != null ? salon.getOwner().getId() : null,
                salon.getName(),
                salon.getDescription(),
                salon.getCity(),
                salon.getRegion(),
                PublicSalonResponse.legacyAddress(salon),
                salon.getCityId(),
                oblastId,
                salon.getDistrictId(),
                salon.getStreet(),
                salon.getBuildingNo(),
                salon.getLocationNote(),
                salon.getPhone(),
                salon.getInstagramUrl(),
                salon.getAvatarUrl(),
                salon.getCoverImageUrl(),
                salon.isActive(),
                salon.isPrimary(),
                salon.getCreatedAt(),
                settlement == null ? null : settlement.settlementType(),
                settlement == null ? null : settlement.hromadaNameUk()
        );
    }
}
