package com.beautica.booking.dto;

import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.common.TimeZones;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * Booking summary DTO used in list endpoints.
 *
 * <p><strong>Access contract:</strong> {@code clientId} and {@code masterId} are exposed.
 * Controller must scope the query to the authenticated actor (own bookings only) before
 * returning this DTO — it must never appear in a publicly accessible or cross-user list.
 *
 * <p><strong>Lazy chain:</strong> {@code from()} calls
 * {@code booking.getMasterService().getServiceDefinition().getName()} — the repository query
 * that loads {@link com.beautica.booking.entity.Booking Booking} instances for this DTO
 * must include {@code LEFT JOIN FETCH b.masterService ms LEFT JOIN FETCH ms.serviceDefinition}
 * to avoid N+1 lazy loads.
 */
public record BookingResponse(
        UUID id,
        UUID clientId,
        UUID masterId,
        UUID masterServiceId,
        String serviceName,
        BookingStatus status,
        ZonedDateTime startsAt,
        ZonedDateTime endsAt,
        BigDecimal priceAtBooking,
        int durationMinutesAtBooking,
        // snapshot fields — reflects price/duration the client agreed to, not current master_services values
        OffsetDateTime createdAt
) {
    public static BookingResponse from(Booking booking) {
        return new BookingResponse(
                booking.getId(),
                booking.getClient().getId(),
                booking.getMaster().getId(),
                booking.getMasterService().getId(),
                booking.getMasterService().getServiceDefinition().getName(),
                booking.getStatus(),
                booking.getStartsAt().atZoneSameInstant(TimeZones.KYIV),
                booking.getEndsAt().atZoneSameInstant(TimeZones.KYIV),
                booking.getPriceAtBooking(),
                booking.getDurationMinutesAtBooking(),
                booking.getCreatedAt().atOffset(ZoneOffset.UTC)
        );
    }
}
