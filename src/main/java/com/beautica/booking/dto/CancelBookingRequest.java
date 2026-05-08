package com.beautica.booking.dto;

import com.beautica.booking.enums.CancellationReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for the cancel-booking endpoint.
 *
 * <p>{@code cancellationReason} is {@code @NotNull} because a client must always
 * supply a reason when cancelling — the constraint is enforced at the controller
 * boundary via {@code @Valid}, so the service layer never receives a null reason.
 * This is distinct from {@link StatusUpdateRequest}, which is shared across
 * decline/not-complete paths where the master-side service validates the reason
 * manually after the request body is deserialized.
 */
public record CancelBookingRequest(
        @NotNull CancellationReason cancellationReason,
        @Size(max = 1000) String comment
) {}
