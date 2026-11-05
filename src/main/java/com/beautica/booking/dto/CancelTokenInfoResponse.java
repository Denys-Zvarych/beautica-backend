package com.beautica.booking.dto;

import com.beautica.booking.enums.BookingStatus;

import java.time.OffsetDateTime;
import java.util.List;

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
 * @param locationNote   phase 435 follow-up — the provider's «how to find us» note for that address (the salon's, else
 *                       the independent master's own {@code location_note}), the SMS rules applied
 *                       ({@code GuestSmsProviderText#locationNote}: one line, ≤ 60 chars, {@code null} when URL-like);
 *                       {@code null} when blank or when {@code address} is {@code null}
 * @param items          phase 436 — every service line of the visit (one for a single booking), ordered by
 *                       {@code startsAt}; never {@code null}. {@code cancellable} above is {@code true} when at least
 *                       one line is cancellable
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
        String address,
        String locationNote,
        List<CancelTokenItemResponse> items
) {

    public CancelTokenInfoResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /** The pre-436 10-field shape (no {@code items}). */
    public CancelTokenInfoResponse(String masterName, String serviceName, OffsetDateTime startsAt, boolean cancellable,
                                   OffsetDateTime windowClosesAt, BookingStatus status, String rebookPath,
                                   String salonName, String address, String locationNote) {
        this(masterName, serviceName, startsAt, cancellable, windowClosesAt, status, rebookPath, salonName, address,
                locationNote, List.of());
    }

    /** The pre-follow-up 9-field shape ({@code locationNote} {@code null}). */
    public CancelTokenInfoResponse(String masterName, String serviceName, OffsetDateTime startsAt, boolean cancellable,
                                   OffsetDateTime windowClosesAt, BookingStatus status, String rebookPath,
                                   String salonName, String address) {
        this(masterName, serviceName, startsAt, cancellable, windowClosesAt, status, rebookPath, salonName, address,
                null, List.of());
    }
}
