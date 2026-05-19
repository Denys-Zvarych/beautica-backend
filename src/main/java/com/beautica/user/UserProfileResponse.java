package com.beautica.user;

import java.util.UUID;

/**
 * Authenticated self view returned by {@code GET/PATCH /api/v1/users/me}.
 *
 * <p>Phase 10.6: surfaces the taxonomy locality ({@code cityId} /
 * {@code districtId}) and — for INDEPENDENT_MASTER — the light structured
 * address so the caller can read back what it wrote. This is the account
 * owner's own record (never a {@code permitAll} response), so the FK and
 * address fields are safe to expose here (§I).
 */
public record UserProfileResponse(
        UUID id,
        String email,
        String role,
        String firstName,
        String lastName,
        String phoneNumber,
        UUID cityId,
        UUID districtId,
        String street,
        String buildingNo,
        String locationNote,
        boolean isActive,
        boolean emailVerified,
        UUID salonId
) {

    public static UserProfileResponse from(User user) {
        return new UserProfileResponse(
                user.getId(),
                user.getEmail(),
                user.getRole().name(),
                user.getFirstName(),
                user.getLastName(),
                user.getPhoneNumber(),
                user.getCityId(),
                user.getDistrictId(),
                user.getStreet(),
                user.getBuildingNo(),
                user.getLocationNote(),
                user.isActive(),
                user.isEmailVerified(),
                user.getSalonId()
        );
    }
}
