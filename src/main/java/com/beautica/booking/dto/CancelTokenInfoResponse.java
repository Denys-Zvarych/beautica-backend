package com.beautica.booking.dto;

import java.time.OffsetDateTime;

/**
 * Public cancel-page payload for {@code GET /api/v1/book/cancel/{token}} (Phase 13.4).
 *
 * <p>Rendered for an unauthenticated guest who opened the cancel link from their
 * confirmation SMS. Carries only the human-facing appointment summary plus the
 * window flags — no internal identifiers (booking id, master id, owner id, phone)
 * are exposed on this {@code permitAll} endpoint (Anti-Bug §I-2).
 *
 * @param masterName     display name of the master the appointment is with
 * @param serviceName    name of the booked service
 * @param startsAt       appointment start instant (ISO-8601 UTC)
 * @param cancellable    whether the appointment is still far enough away to be cancelled
 *                       ({@code startsAt} is after {@code now + cancelWindowHours})
 * @param windowClosesAt the latest instant at which a cancellation is still permitted
 *                       ({@code startsAt - cancelWindowHours}); shown to the user
 */
public record CancelTokenInfoResponse(
        String masterName,
        String serviceName,
        OffsetDateTime startsAt,
        boolean cancellable,
        OffsetDateTime windowClosesAt
) {
}
