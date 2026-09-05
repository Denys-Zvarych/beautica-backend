package com.beautica.salon.dto;

import com.beautica.auth.Role;
import com.beautica.booking.dto.BookingDetailResponse;
import com.beautica.master.entity.Master;
import com.beautica.user.User;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One row of {@code GET /api/v1/salons/{salonId}/staff} (Phase 21.5) — a management-scoped roster
 * entry covering BOTH a {@code Master} (any {@link com.beautica.master.entity.MasterType} bound
 * to the salon) and a {@code SALON_ADMIN} {@link User}, so the mobile Персонал tab and the
 * staff-member detail screen can render either kind from one read.
 *
 * <p>Unlike {@code MasterSummaryResponse}/the public {@code MasterDetailResponse.fromPublic},
 * {@code phoneNumber} and {@code instagram} are returned UNMASKED here — this DTO backs a
 * management-gated endpoint only ({@code @authz.canManageSalon}), never a {@code permitAll} read.
 * Do not reuse this record, or loosen the public DTOs' masking, to shortcut some other read path.
 *
 * @param masterId {@code null} for a {@code SALON_ADMIN} entry — admins have no {@code Master} row
 * @param avgRating {@code null} for an admin, and for a master with zero reviews (never a stored
 *                  {@code 0.00} — see {@link BookingDetailResponse#masterAvgRatingOrNull})
 * @param serviceCount count of the master's currently ACTIVE {@code master_services} rows;
 *                     {@code 0} for an admin
 */
public record SalonStaffMemberResponse(
        UUID userId,
        UUID masterId,
        Role role,
        String firstName,
        String lastName,
        String professionalTitle,
        String avatarUrl,
        String phoneNumber,
        String instagram,
        String bio,
        BigDecimal avgRating,
        int reviewCount,
        long serviceCount
) {

    /**
     * @param master the master entity; {@code master.getUser()} must already be initialised
     *               (the roster's caller loads masters via a {@code JOIN FETCH user} query)
     * @param serviceCount the master's active service count, resolved in the SAME batch query
     *                     for the whole roster — never a per-master lookup (Anti-Bug §E-3)
     */
    public static SalonStaffMemberResponse fromMaster(Master master, long serviceCount) {
        User user = master.getUser();
        return new SalonStaffMemberResponse(
                user.getId(),
                master.getId(),
                user.getRole(),
                user.getFirstName(),
                user.getLastName(),
                user.getProfessionalTitle(),
                user.getAvatarUrl(),
                user.getPhoneNumber(),
                user.getInstagram(),
                user.getBio(),
                BookingDetailResponse.masterAvgRatingOrNull(master.getReviewCount(), master.getAvgRating()),
                master.getReviewCount(),
                serviceCount
        );
    }

    /**
     * Master-only fields ({@code masterId}, {@code avgRating}, {@code reviewCount},
     * {@code serviceCount}) are null/zero. {@code bio} is deliberately withheld ({@code null})
     * for admins too — the admin branch of the roster/detail screens renders identity + phone
     * only, and this endpoint's unmasked-PII carve-out should carry no more than what the screen
     * actually uses.
     */
    public static SalonStaffMemberResponse fromAdmin(User user) {
        return new SalonStaffMemberResponse(
                user.getId(),
                null,
                user.getRole(),
                user.getFirstName(),
                user.getLastName(),
                user.getProfessionalTitle(),
                user.getAvatarUrl(),
                user.getPhoneNumber(),
                user.getInstagram(),
                null,
                null,
                0,
                0L
        );
    }
}
