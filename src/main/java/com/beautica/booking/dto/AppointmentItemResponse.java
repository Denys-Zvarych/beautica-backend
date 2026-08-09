package com.beautica.booking.dto;

import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.booking.enums.CancellationReason;
import com.beautica.common.TimeZones;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * One service line of a multi-service visit (BE-3) — the client-facing projection of a single
 * chained {@code bookings} row that belongs to an {@link com.beautica.booking.entity.Appointment}.
 * Carries the per-item window and the frozen price snapshot, so the mobile visit screen can render
 * the visit timeline without a second round-trip per service.
 *
 * <p>{@code startsAt}/{@code endsAt} are Kyiv-zoned, matching {@link BookingDetailResponse}. The
 * item's {@code endsAt} already includes that service's own {@code bufferMinutesAfter} (D4). AT
 * CREATE TIME the items are chained back-to-back, so the next item's {@code startsAt} equals this
 * item's {@code endsAt} — but that is true only until an item is rescheduled individually (phase
 * 30.1's relaxed contiguity: {@code AppointmentTransitionService#rescheduleAppointmentItem} moves
 * exactly one item, leaving every sibling's window byte-for-byte unchanged). After such a move,
 * items may be separated by legal gaps; {@code items[]} stays sorted by {@code startsAt} ascending,
 * but adjacency must never be assumed.
 *
 * <p>{@code status} is the PER-ITEM status: a service line may be {@code DECLINED} on its own (via
 * {@code PATCH /appointments/{id}/services/{bookingId}/decline}) while its siblings stay
 * {@code CONFIRMED} — so the visit screen can strike through the one lost service without cancelling
 * the visit. {@code cancellationReason}/{@code providerComment} are that line's own terminal
 * metadata (the per-service decline writes the note to the CHILD row); they are {@code null} on a
 * CONFIRMED line. Whole-visit decline/no-show notes live on the {@link AppointmentDetailResponse}
 * header, not here.
 *
 * @param priceMaxAtBooking the frozen RANGE ceiling for THIS service, or {@code null} when it was a
 *   single price at booking time — same per-row semantics as {@link BookingDetailResponse}. Never
 *   re-derived on read.
 */
public record AppointmentItemResponse(
        UUID bookingId,
        UUID masterServiceId,
        String serviceName,
        @Schema(description = "The per-item status. A single service line may be DECLINED "
                + "independently of its siblings (per-service decline); CONFIRMED means still booked.")
        BookingStatus status,
        ZonedDateTime startsAt,
        ZonedDateTime endsAt,
        int durationMinutesAtBooking,
        BigDecimal priceAtBooking,
        @Schema(types = {"number", "null"}, nullable = true,
                description = "The frozen RANGE ceiling for this service, present only when it was a "
                        + "genuine range (no priceOverride) at booking time. Null = single price.")
        BigDecimal priceMaxAtBooking,
        @Schema(types = {"string", "null"}, nullable = true,
                description = "This service line's own cancellation reason — non-null only when the "
                        + "line is terminal (e.g. PROVIDER_UNAVAILABLE for a per-service decline).")
        CancellationReason cancellationReason,
        @Schema(types = {"string", "null"}, nullable = true,
                description = "Provider note written on THIS line's per-service decline. Mutually "
                        + "visible to the client (locked booking-notes decision). Null on a CONFIRMED "
                        + "line and on whole-visit terminations (whose note lives on the header).")
        String providerComment
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
                booking.getStatus(),
                booking.getStartsAt().atZoneSameInstant(TimeZones.KYIV),
                booking.getEndsAt().atZoneSameInstant(TimeZones.KYIV),
                booking.getDurationMinutesAtBooking(),
                booking.getPriceAtBooking(),
                booking.getPriceMaxAtBooking(),
                booking.getCancellationReason(),
                booking.getProviderComment());
    }
}
