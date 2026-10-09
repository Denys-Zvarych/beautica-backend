package com.beautica.booking.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response shape for {@code GET /bookings/me/pending-actions/count} and {@code GET
 * /bookings/salon/{salonId}/pending-actions/count} (Phase 357) — the «Архів» badge number.
 * Never cached: it must be fresh right after a complete / rate-client mutation.
 */
public record PendingBookingActionsCountResponse(
        @Schema(description = "Total bookings still needing a provider action: toClose + toRateClient. "
                + "Uncapped; the client caps its display.")
        long count,
        @Schema(description = "CONFIRMED bookings whose end has passed (offer «Завершити» / «Не відбувся»).")
        long toClose,
        @Schema(description = "COMPLETED bookings with a registered client and no client review yet "
                + "(offer «Залишити відгук про клієнта»).")
        long toRateClient
) {
    public static PendingBookingActionsCountResponse of(long toClose, long toRateClient) {
        return new PendingBookingActionsCountResponse(toClose + toRateClient, toClose, toRateClient);
    }
}
