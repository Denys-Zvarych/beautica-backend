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
 *
 * <p>Phase 265 adds {@code hasMasterProfile} — see the component's own javadoc below. Like every
 * other field here it describes the caller to themselves; it must never be copied onto a public
 * master or salon DTO, where the same fact is already expressed structurally by whether the person
 * appears in the roster at all.
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
        String professionalTitle,
        boolean isActive,
        boolean emailVerified,
        UUID salonId,

        /**
         * Whether the signed-in user currently has an <em>active</em> master row of type
         * {@code SALON_OWNER} — i.e. the state of the owner's «Я також працюю як майстер»
         * toggle (Phase 265). Derived on every read from the row itself; there is deliberately
         * no {@code has_master_profile} column and no migration, because the toggle is the row
         * and a column would be a second source of truth that drifts the moment either of
         * {@code POST}/{@code DELETE /api/v1/salons/&#123;salonId&#125;/master} is touched.
         *
         * <p>Defaults to ON in practice: {@code SalonService.createSalon} auto-creates the
         * owner-master row on first-salon registration, so every owner registered to date reads
         * {@code true}. A client that assumes {@code false} until proven otherwise will show
         * existing owners an unchecked box describing a state they are not in.
         *
         * <p>{@code false} for every non-{@code SALON_OWNER} role — a {@code CLIENT} or a
         * {@code SALON_ADMIN} has no owner-master row by construction, and an
         * {@code INDEPENDENT_MASTER}/{@code SALON_MASTER}'s row is of a different type.
         *
         * <p>It is a <b>render gate, not a data source</b>: the profile section it unlocks is fed
         * by {@code GET /api/v1/masters/me}, which Phase 265 widened to {@code SALON_OWNER} and
         * which already carries {@code bio}, {@code avgRating}, {@code reviewCount},
         * {@code professionalTitle} and {@code avatarUrl}. The boolean exists so the app does not
         * fire that call speculatively and take a 404 for an owner who has opted out.
         */
        boolean hasMasterProfile
) {

    // NOTE — there is deliberately NO `from(User)` convenience overload.
    // It existed until the Phase 265 audit and hard-coded `hasMasterProfile = false`; the PATCH
    // write-back path (UserService#updateProfile, also reached by
    // IndependentMasterController#updateLocality) used it and therefore answered `false` for an
    // opted-in owner while GET /users/me answered `true` — one non-nullable field with two
    // meanings on the same wire type. A single four-argument factory forces every call site to
    // state what it knows about the flag, so the bug cannot silently come back through a
    // convenience shortcut. Callers with nothing to resolve pass `from(user, null, null, false)`
    // explicitly.

    /**
     * Builds a response with a pre-resolved {@code districtName} and
     * {@code oblastId}. The GET-profile read path resolves the district label
     * (only when {@code districtId != null}) and the oblast id (only when
     * {@code cityId != null}) and passes them here; {@code cityName}/
     * {@code oblastName} are always read off the denormalised
     * {@code users.city}/{@code users.region} columns.
     *
     * @param user             the account owner's record
     * @param districtName     resolved {@code name_uk} of the user's district, or
     *                         {@code null} when no district is set / unresolved
     * @param oblastId         resolved parent oblast id of the user's city, or
     *                         {@code null} when no city is set / unresolved
     * @param hasMasterProfile whether an active {@code SALON_OWNER}-type master row exists for
     *                         this user — resolved by the caller, since this record has no
     *                         repository access
     */
    public static UserProfileResponse from(
            User user, String districtName, UUID oblastId, boolean hasMasterProfile) {
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
                user.getProfessionalTitle(),
                user.isActive(),
                user.isEmailVerified(),
                user.getSalonId(),
                hasMasterProfile
        );
    }
}
