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
 *
 * <p>{@code oblastId} (the parent oblast UUID of {@code cityId}) is surfaced so
 * the mobile Location-edit screen can pre-select the oblast tier of the
 * oblast→city→district cascade directly, instead of scanning every oblast's city
 * list to discover which oblast owns the user's city. It is resolved on demand
 * only when a {@code cityId} is present (one scalar lookup), {@code null}
 * otherwise. This is the account owner's own record (never a {@code permitAll}
 * response), so exposing the oblast FK here is safe (§I).
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
        UUID oblastId,
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
     * Builds a response with no resolved {@code districtName} / {@code oblastId}
     * (passes {@code null} for both). Used where a taxonomy lookup is unnecessary
     * — e.g. the PATCH write-back path, or callers that do not have the location
     * repositories to hand. {@code cityName}/{@code oblastName} are still
     * populated from the denormalised entity columns.
     */
    public static UserProfileResponse from(User user) {
        return from(user, null, null);
    }

    /**
     * Builds a response with a pre-resolved {@code districtName} and
     * {@code oblastId}. The GET-profile read path resolves the district label
     * (only when {@code districtId != null}) and the oblast id (only when
     * {@code cityId != null}) and passes them here; {@code cityName}/
     * {@code oblastName} are always read off the denormalised
     * {@code users.city}/{@code users.region} columns.
     *
     * @param user         the account owner's record
     * @param districtName resolved {@code name_uk} of the user's district, or
     *                     {@code null} when no district is set / unresolved
     * @param oblastId     resolved parent oblast id of the user's city, or
     *                     {@code null} when no city is set / unresolved
     */
    public static UserProfileResponse from(User user, String districtName, UUID oblastId) {
        return new UserProfileResponse(
                user.getId(),
                user.getEmail(),
                user.getRole().name(),
                user.getFirstName(),
                user.getLastName(),
                user.getPhoneNumber(),
                user.getCityId(),
                user.getDistrictId(),
                oblastId,
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
