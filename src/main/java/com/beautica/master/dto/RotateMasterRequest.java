package com.beautica.master.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Request body for {@code PATCH /api/v1/masters/{masterId}/salon} (Phase 21.3).
 *
 * <p>{@code destinationSalonId} must resolve to an active salon owned by the same
 * {@code SALON_OWNER} as the master's current salon — enforced in
 * {@code MasterService.rotateMasterToSalon} via
 * {@link com.beautica.common.security.AuthorizationService#salonsShareOwner}.
 */
public record RotateMasterRequest(
        @NotNull(message = "Destination salon id is required")
        UUID destinationSalonId
) {
}
