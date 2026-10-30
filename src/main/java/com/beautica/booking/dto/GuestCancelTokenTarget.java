package com.beautica.booking.dto;

import com.beautica.booking.enums.BookingSource;
import com.beautica.booking.enums.BookingStatus;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Phase 407 — what a guest cancel token points at, read as a JPQL constructor projection (no entity graph).
 *
 * @param bookingId  the standalone booking, or a visit's first item (the {@code telegram_link_tokens.booking_id})
 * @param source     booking source (a visit: the header's)
 * @param status     booking status (a visit: the header's)
 * @param guestPhone E.164 guest phone; {@code null} once anonymised. Personal data: never logged
 * @param startsAt   start of the booking / of the visit's first item
 */
public record GuestCancelTokenTarget(UUID bookingId, BookingSource source, BookingStatus status,
                                     String guestPhone, OffsetDateTime startsAt) {

    public GuestCancelTokenTarget {
        Objects.requireNonNull(bookingId, "bookingId");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(startsAt, "startsAt");
    }

    /** Redacted — the generated {@code toString} would print the guest phone. */
    @Override
    public String toString() {
        return "GuestCancelTokenTarget[bookingId=" + bookingId + ", source=" + source + ", status=" + status
                + ", guestPhone=<redacted>, startsAt=" + startsAt + "]";
    }
}
