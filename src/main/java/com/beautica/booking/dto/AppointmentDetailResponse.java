package com.beautica.booking.dto;

import com.beautica.auth.Role;
import com.beautica.booking.entity.Appointment;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.common.TimeZones;
import com.beautica.master.entity.Master;
import com.beautica.salon.entity.Salon;
import com.beautica.user.User;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The enriched view of a multi-service single-visit appointment (BE-3): a visit HEADER plus the
 * ordered {@code items[]} of the services performed back-to-back by one master. Follows the shape of
 * {@link BookingDetailResponse} — the same master-summary + Kyiv-zoned window fields — aggregated to
 * the visit level.
 *
 * <p><b>Header window.</b> {@code startsAt} is the first item's start and {@code endsAt} the last
 * item's end, so the pair spans the whole contiguous block (including every service's trailing
 * buffer). {@code totalDurationMinutes} is the block length ({@code endsAt − startsAt}), which by
 * construction equals Σ (item duration + item buffer) — the exact block BE-2 sized the offered slot
 * to.
 *
 * <p><b>Header price.</b> {@code totalPrice} is Σ of the item price floors. {@code totalPriceMax} is
 * Σ over all items of {@code priceMaxAtBooking ?? priceAtBooking}, but is emitted ONLY when at least
 * one item was a genuine range at booking time — otherwise {@code null}, meaning "single total
 * price, render {@code totalPrice} alone". This mirrors the per-row {@code priceMaxAtBooking} rule on
 * {@link BookingDetailResponse} lifted to the visit total: a visit is a range iff any of its
 * services was.
 *
 * <p>All price/duration values are frozen per item at creation and never re-derived here.
 */
public record AppointmentDetailResponse(
        UUID id,
        BookingStatus status,
        UUID masterId,
        String masterFirstName,
        String masterLastName,
        @Schema(types = {"string", "null"}, nullable = true,
                description = "The master's professional title/headline. Nullable — a master may "
                        + "never have set one.")
        String masterProfessionalTitle,
        String masterAvatarUrl,
        Role masterType,
        @Schema(types = {"string", "null"}, nullable = true,
                description = "The salon name, or null for an independent master.")
        String salonName,
        ZonedDateTime startsAt,
        ZonedDateTime endsAt,
        int totalDurationMinutes,
        BigDecimal totalPrice,
        @Schema(types = {"number", "null"}, nullable = true,
                description = "The summed range ceiling of the visit, present ONLY when at least one "
                        + "service was a genuine range at booking time. Null means a single total "
                        + "price — render totalPrice alone. Never re-derived on read.")
        BigDecimal totalPriceMax,
        @Schema(types = {"string", "null"}, nullable = true,
                description = "The client's booking-creation note for the whole visit.")
        String clientComment,
        OffsetDateTime createdAt,
        List<AppointmentItemResponse> items
) {

    /**
     * Builds the visit view from the appointment header and its ordered, fully-hydrated chained
     * booking rows. {@code orderedItems} MUST be non-empty and sorted by {@code startsAt} ascending
     * (as {@code BookingRepository#findByAppointmentIdWithGraph} returns them), and each row MUST
     * carry its {@code master.user}, {@code master.salon} and {@code masterService.serviceDefinition}
     * graph hydrated. The master summary is read off the first item (single master per visit — a
     * locked invariant).
     */
    public static AppointmentDetailResponse from(Appointment appointment, List<Booking> orderedItems) {
        Booking first = orderedItems.get(0);
        Booking last = orderedItems.get(orderedItems.size() - 1);
        Master master = first.getMaster();
        User masterUser = master.getUser();
        Salon salon = master.getSalon();

        BigDecimal totalPrice = BigDecimal.ZERO;
        BigDecimal totalCeiling = BigDecimal.ZERO;
        boolean anyRange = false;
        for (Booking item : orderedItems) {
            totalPrice = totalPrice.add(item.getPriceAtBooking());
            if (item.getPriceMaxAtBooking() != null) {
                anyRange = true;
                totalCeiling = totalCeiling.add(item.getPriceMaxAtBooking());
            } else {
                totalCeiling = totalCeiling.add(item.getPriceAtBooking());
            }
        }

        int totalDurationMinutes = (int) Duration.between(
                first.getStartsAt(), last.getEndsAt()).toMinutes();

        List<AppointmentItemResponse> items = orderedItems.stream()
                .map(AppointmentItemResponse::from)
                .toList();

        return new AppointmentDetailResponse(
                appointment.getId(),
                appointment.getStatus(),
                master.getId(),
                masterUser.getFirstName(),
                masterUser.getLastName(),
                masterUser.getProfessionalTitle(),
                masterUser.getAvatarUrl(),
                masterUser.getRole(),
                salon != null ? salon.getName() : null,
                first.getStartsAt().atZoneSameInstant(TimeZones.KYIV),
                last.getEndsAt().atZoneSameInstant(TimeZones.KYIV),
                totalDurationMinutes,
                totalPrice,
                anyRange ? totalCeiling : null,
                appointment.getClientComment(),
                appointment.getCreatedAt().atOffset(ZoneOffset.UTC),
                items);
    }
}
