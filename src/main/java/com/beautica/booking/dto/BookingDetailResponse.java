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
 * PII access contract: the controller MUST verify the caller is the booking's client
 * or the assigned master/owner before invoking {@code from(booking)}.
 *
 * <p>{@code clientFirstName}/{@code clientLastName} are intentionally visible to SALON_MASTER
 * actors — the master needs the client's name on their calendar. No field-level role
 * differentiation is applied. If {@code canViewBooking} scope ever widens, audit this DTO.
 */
public record BookingDetailResponse(
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
        OffsetDateTime createdAt,
        String clientFirstName,
        String clientLastName,
        String masterFirstName,
        String masterLastName,
        String clientComment,
        String providerComment
) {
    public static BookingDetailResponse from(Booking booking) {
        return new BookingDetailResponse(
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
                booking.getCreatedAt().atOffset(ZoneOffset.UTC),
                booking.getClient().getFirstName(),
                booking.getClient().getLastName(),
                // Caller query must JOIN FETCH master LEFT JOIN FETCH master.user to avoid N+1
                booking.getMaster().getUser().getFirstName(),
                booking.getMaster().getUser().getLastName(),
                booking.getClientComment(),
                booking.getProviderComment()
        );
    }
}
