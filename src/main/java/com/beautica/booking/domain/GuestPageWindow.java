package com.beautica.booking.domain;

import com.beautica.booking.enums.BookingStatus;

import java.time.OffsetDateTime;

/**
 * Phase 435 audit-fix — whether a guest's own page ({@code GET /book/cancel/{token}}) is past its read-only life: the
 * booking (or visit header) is terminal — anything but {@code CONFIRMED} — AND it started more than
 * {@code app.booking.terminal-view-days} days ago. Both cancellation services 404 such a token exactly like an unknown
 * one, so an old SMS link stops disclosing the visit. A {@code CONFIRMED} booking is never cut off here.
 */
public final class GuestPageWindow {

    private GuestPageWindow() {
    }

    /** {@code true} when the page must 404; the boundary instant itself ({@code startsAt == now - days}) is still shown. */
    public static boolean expired(BookingStatus status, OffsetDateTime startsAt, OffsetDateTime now, int terminalViewDays) {
        return status != BookingStatus.CONFIRMED && startsAt.isBefore(now.minusDays(terminalViewDays));
    }
}
