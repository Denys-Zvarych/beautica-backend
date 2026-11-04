package com.beautica.booking.dto;

import com.beautica.booking.enums.BookingStatus;

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
 * @param status         phase 435 — the booking's (a visit: its header's) status. Not {@code CONFIRMED} = a
 *                       read-only view (provider-cancelled, declined, completed ...): {@code cancellable} is then
 *                       always {@code false} and {@code POST} still answers 404
 * @param rebookPath     phase 435 — website-relative «записатись знову» path ({@code /book/{slug}} or
 *                       {@code /book/s/{slug}}), or {@code null} when there is none (see {@code GuestRebookPaths})
 * @param salonName      phase 435 — the salon's name for a salon booking; {@code null} for an independent master
 * @param address        phase 435 — where the visit happens, the same text the reminder SMS shows
 *                       ({@code GuestVisitAddress}: salon street + building, else its legacy free-text address; an
 *                       independent master's own street + building); {@code null} when there is none
 */
public record CancelTokenInfoResponse(
        String masterName,
        String serviceName,
        OffsetDateTime startsAt,
        boolean cancellable,
        OffsetDateTime windowClosesAt,
        BookingStatus status,
        String rebookPath,
        String salonName,
        String address
) {
}
