package com.beautica.booking.dto;

import com.beautica.booking.enums.BookingStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Phase 436 — one service line of the guest cancel page ({@code GET/POST /api/v1/book/cancel/{token}}).
 *
 * <p>{@code bookingId} is exposed on this {@code permitAll} endpoint on purpose: the guest names the lines to cancel by
 * it. It is reachable only through the guest's own one-time token and identifies that guest's own booking — no
 * master, salon, owner or client id is carried (Anti-Bug §I-2).
 *
 * @param bookingId       the booking (service line) id — the value to send in {@code bookingIds}
 * @param serviceName     the service's display name
 * @param startsAt        the line's start instant (ISO-8601)
 * @param durationMinutes the service duration frozen at booking time
 * @param status          the line's status
 * @param cancellable     {@code status == CONFIRMED} and {@code startsAt} is after {@code now + cancel window}
 */
public record CancelTokenItemResponse(
        UUID bookingId,
        String serviceName,
        OffsetDateTime startsAt,
        int durationMinutes,
        BookingStatus status,
        boolean cancellable
) {
}
