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
 *
 * <p>Resolved locality NAMES ({@code cityName}, {@code oblastName},
 * {@code districtName}) are surfaced alongside the FK ids so the mobile client
 * home hub can render the user's city without a second round-trip to the
 * taxonomy endpoints. {@code cityName}/{@code oblastName} are read straight off
 * the denormalised {@code users.city}/{@code users.region} columns (written by
 * {@code UserService.writeCityDisplayStrings} on every locality write — zero
 * extra query); {@code districtName} is resolved on demand only when a
 * {@code districtId} is present.
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
        String cityName,
        String oblastName,
        String districtName,
        String street,
        String buildingNo,
        String locationNote,
        String bio,
        String instagram,
        boolean isActive,
        boolean emailVerified,
        UUID salonId
) {

    /**
     * Builds a response with no resolved {@code districtName} (passes
     * {@code null}). Used where a district lookup is unnecessary — e.g. callers
     * that do not have a {@link com.beautica.location.repository.CityDistrictRepository}
     * to hand, or where the user has no district. {@code cityName}/
     * {@code oblastName} are still populated from the denormalised entity
     * columns.
     */
    public static UserProfileResponse from(User user) {
        return from(user, null);
    }

    /**
     * Builds a response with a pre-resolved {@code districtName}. The GET-profile
     * read path resolves the district label (only when {@code districtId != null})
     * and passes it here; {@code cityName}/{@code oblastName} are always read off
     * the denormalised {@code users.city}/{@code users.region} columns.
     *
     * @param user         the account owner's record
     * @param districtName resolved {@code name_uk} of the user's district, or
     *                     {@code null} when no district is set / unresolved
     */
    public static UserProfileResponse from(User user, String districtName) {
        return new UserProfileResponse(
                user.getId(),
                user.getEmail(),
                user.getRole().name(),
                user.getFirstName(),
                user.getLastName(),
                user.getPhoneNumber(),
                user.getCityId(),
                user.getDistrictId(),
                user.getCity(),
                user.getRegion(),
                districtName,
                user.getStreet(),
                user.getBuildingNo(),
                user.getLocationNote(),
                user.getBio(),
                user.getInstagram(),
                user.isActive(),
                user.isEmailVerified(),
                user.getSalonId()
        );
    }
}
