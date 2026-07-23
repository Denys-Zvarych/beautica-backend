package com.beautica.booking.dto;

import com.beautica.booking.entity.Booking;
import com.beautica.common.TimeZones;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * One service line of a multi-service visit (BE-3) — the client-facing projection of a single
 * chained {@code bookings} row that belongs to an {@link com.beautica.booking.entity.Appointment}.
 * Carries the per-item window and the frozen price snapshot, so the mobile visit screen can render
 * the back-to-back timeline without a second round-trip per service.
 *
 * <p>{@code startsAt}/{@code endsAt} are Kyiv-zoned, matching {@link BookingDetailResponse}. The
 * item's {@code endsAt} already includes that service's own {@code bufferMinutesAfter} (D4), so the
 * next item's {@code startsAt} equals this item's {@code endsAt}.
 *
 * @param priceMaxAtBooking the frozen RANGE ceiling for THIS service, or {@code null} when it was a
 *   single price at booking time — same per-row semantics as {@link BookingDetailResponse}. Never
 *   re-derived on read.
 */
public record AppointmentItemResponse(
        UUID bookingId,
        UUID masterServiceId,
        String serviceName,
        ZonedDateTime startsAt,
        ZonedDateTime endsAt,
        int durationMinutesAtBooking,
        BigDecimal priceAtBooking,
        @Schema(types = {"number", "null"}, nullable = true,
                description = "The frozen RANGE ceiling for this service, present only when it was a "
                        + "genuine range (no priceOverride) at booking time. Null = single price.")
        BigDecimal priceMaxAtBooking
) {

    /**
     * Maps one chained booking row to its item projection. The caller MUST have hydrated
     * {@code b.masterService.serviceDefinition} (for the service name) — see
     * {@code BookingRepository#findByAppointmentIdWithGraph}.
     */
    public static AppointmentItemResponse from(Booking booking) {
        return new AppointmentItemResponse(
                booking.getId(),
                booking.getMasterService().getId(),
                booking.getMasterService().getServiceDefinition().getName(),
                booking.getStartsAt().atZoneSameInstant(TimeZones.KYIV),
                booking.getEndsAt().atZoneSameInstant(TimeZones.KYIV),
                booking.getDurationMinutesAtBooking(),
                booking.getPriceAtBooking(),
                booking.getPriceMaxAtBooking());
    }
}
