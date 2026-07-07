package com.beautica.salon.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Request body for {@code PATCH /api/v1/salons/{salonId}/admins/{userId}/salon} (Phase 21.3).
 *
 * <p>{@code destinationSalonId} must resolve to an active salon owned by the same
 * {@code SALON_OWNER} as the source salon in the path — enforced in
 * {@code SalonService.rotateAdmin} via
 * {@link com.beautica.common.security.AuthorizationService#salonsShareOwner}.
 */
public record RotateAdminRequest(
        @NotNull(message = "Destination salon id is required")
        UUID destinationSalonId
) {
}
