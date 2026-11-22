package com.beautica.client.provider.dto;

import com.beautica.booking.enums.BookingStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * B508 — one visit in a client's history with the viewer (CLI-45): only the rows in the viewer's scope (a SALON_MASTER
 * sees only their own services of a multi-master visit). No comments, no cancellation notes — those stay on booking
 * detail (BOOK-40); each item's {@code bookingId} opens it.
 *
 * @param visitKey appointment id, or the booking id of a single-row booking
 * @param startsAt earliest row start in scope
 * @param salonId  null for an independent master's visit
 */
public record ClientVisitResponse(
        UUID visitKey,
        OffsetDateTime startsAt,
        UUID salonId,
        List<Item> items
) {

    public record Item(
            UUID bookingId,
            String serviceName,
            BookingStatus status,
            OffsetDateTime startsAt,
            UUID masterId,
            String masterDisplayName,
            BigDecimal priceAtBooking,
            BigDecimal priceMaxAtBooking
    ) {
    }
}
