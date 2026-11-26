package com.beautica.client.provider.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;

import java.util.UUID;

/**
 * B564 — which client to remove from (or restore to) the caller's «Клієнти» list: a registered client by
 * {@code clientId}, or a guest by any of their bookings in scope ({@code guestBookingId}, the list row's
 * {@code anchorBookingId}). Exactly one (D6), else 400; the phone is never sent.
 */
public record ClientListRemovalRequest(
        @Schema(description = "Registered client's user id (list row kind REGISTERED)", nullable = true)
        UUID clientId,
        @Schema(description = "A guest's booking id in scope (list row anchorBookingId, kind GUEST)", nullable = true)
        UUID guestBookingId
) {

    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = "Exactly one of clientId, guestBookingId is required")
    public boolean isExactlyOneIdentity() {
        return (clientId == null) != (guestBookingId == null);
    }
}
