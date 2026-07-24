package com.beautica.booking.dto;

import com.beautica.booking.enums.CancellationReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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
        @NotNull(message = "Cancellation reason is required") CancellationReason cancellationReason,
        // max=1000 mirrors booking.client_comment VARCHAR(1000) (§A). Control-char ban
        // prevents embedded NUL reaching the DB as a 500 instead of 400; line breaks
        // (\n, \r) and tabs (\t) are permitted — this is a long-form free-text field.
        @Size(max = 1000, message = "Comment must be at most 1000 characters")
        @Pattern(regexp = "^[^\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]*$",
                message = "Comment must not contain control characters other than line breaks and tabs")
        String comment
) {}
