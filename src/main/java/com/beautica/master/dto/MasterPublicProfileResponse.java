package com.beautica.master.dto;

/**
 * Slim response returned by {@code PATCH /api/v1/independent-masters/me/profile}.
 *
 * <p>Echoes back only the fields the endpoint is allowed to modify.
 * {@link com.beautica.user.UserProfileResponse} (16+ fields) is reserved for the
 * full account-owner self-view ({@code GET /api/v1/users/me}) where the wider
 * field set is contextually appropriate.
 *
 * <p>Keeping the response small (§I) avoids exposing internal flags
 * ({@code isActive}, {@code emailVerified}, {@code salonId}, locality FKs) on
 * every write, and reduces the payload size the mobile client must parse.
 */
public record MasterPublicProfileResponse(
        String firstName,
        String lastName,
        String phoneNumber,
        String bio,
        String instagram,
        String professionalTitle
) {}
