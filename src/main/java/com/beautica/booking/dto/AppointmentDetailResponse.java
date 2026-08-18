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
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * The enriched view of a multi-service single-visit appointment (BE-3): a visit HEADER plus the
 * ordered {@code items[]} of the services performed back-to-back by one master. Follows the shape of
 * {@link BookingDetailResponse} — the same master-summary + Kyiv-zoned window fields — aggregated to
 * the visit level.
 *
 * <p><b>Header window.</b> {@code startsAt} is {@code min(startsAt)} over every item (the loader
 * orders by {@code startsAt} ascending, so this is item 0) and {@code endsAt} is {@code max(endsAt)}
 * over every item — an explicit reduction, NOT {@code orderedItems.get(size-1).getEndsAt()} (phase
 * 30.1/30.7): once a single item can be rescheduled independently of its siblings (relaxed
 * contiguity — see {@code AppointmentTransitionService#rescheduleAppointmentItem}), the row with the
 * greatest {@code startsAt} is no longer necessarily the row with the greatest {@code endsAt} — a
 * terminal sibling (exempt from every overlap guard) may span past the last CONFIRMED item.
 * {@code totalDurationMinutes} is the SUM of non-excluded PER-ITEM durations (see below) — it equals
 * {@code endsAt − startsAt} only while the visit remains contiguous, and must never be derived from
 * the header window.
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
                description = "The name of the salon THIS VISIT was booked at (the visit's own "
                        + "salon snapshot), or null when the visit was with an independent master. "
                        + "Not the master's current affiliation — a master who has since moved "
                        + "salons does not rewrite a past visit's premises.")
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
        List<AppointmentItemResponse> items,
        // ── BE-5 visit-detail enrichment (mirrors BookingDetailResponse) ─────────────
        @Schema(types = {"string", "null"}, nullable = true,
                description = "Written by the provider on the visit /decline or /not-complete. Shown "
                        + "to the CLIENT on both DECLINED and NOT_COMPLETED visits — intentional, by "
                        + "the locked \"all notes visible for all sides\" decision, NOT a privacy "
                        + "leak. Do not suppress for any audience. Same field/rule as "
                        + "BookingDetailResponse.providerComment, lifted to the visit header.")
        String providerComment,
        @Schema(types = {"string", "null"}, nullable = true,
                description = "Written by the CLIENT on the visit /cancel — the symmetric counterpart "
                        + "of providerComment, shown to the provider. Only ever non-null on a "
                        + "CANCELLED visit. Same field/rule as "
                        + "BookingDetailResponse.clientCancellationNote.")
        String clientCancellationNote,
        @Schema(types = {"string", "null"}, nullable = true,
                description = "Discovery city label (Ukrainian). Resolved by the service through the "
                        + "same district-primary DiscoveryLocationResolver seam as "
                        + "BookingDetailResponse — salon locality when salon-employed, else the "
                        + "master's own user row.")
        String cityLabel,
        @Schema(types = {"string", "null"}, nullable = true,
                description = "Discovery district label (Ukrainian). Same resolution as cityLabel.")
        String districtLabel,
        @Schema(types = {"string", "null"}, nullable = true,
                description = "Arrival street — the BOOKED salon's when the visit was made at a "
                        + "salon, else the master's own. Same salon-vs-independent rule as "
                        + "BookingDetailResponse.street, resolved against the visit's own salon "
                        + "snapshot: a salon-employed master's PERSONAL street never leaks onto a "
                        + "salon visit, AND a master who has since moved salons cannot cause this "
                        + "visit to display the address of premises it was never booked at.")
        String street,
        @Schema(types = {"string", "null"}, nullable = true, description = "Arrival building number.")
        String buildingNo,
        @Schema(types = {"string", "null"}, nullable = true,
                description = "Provider's free-text arrival hint (e.g. \"3-й поверх, код 1234\"). "
                        + "Same salon-vs-independent resolution as street/buildingNo, against the "
                        + "salon THIS VISIT was booked at — never the master's personal note, and "
                        + "never a salon the master merely works at today.")
        String locationNote
) {

    /**
     * Builds the visit view from the appointment header and its ordered, fully-hydrated chained
     * booking rows. {@code orderedItems} MUST be non-empty and sorted by {@code startsAt} ascending
     * (as {@code BookingRepository#findByAppointmentIdWithGraph} returns them), and each row MUST
     * carry its {@code master.user}, <b>{@code booking.salon}</b> and
     * {@code masterService.serviceDefinition} graph hydrated. The master summary is read off the
     * first item (single master per visit — a locked invariant), and so is the address block,
     * which since phase 242 comes from that item's own salon snapshot rather than
     * {@code master.getSalon()}.
     */
    public static AppointmentDetailResponse from(
            Appointment appointment, List<Booking> orderedItems,
            String cityLabel, String districtLabel) {
        Booking first = orderedItems.get(0);
        Master master = first.getMaster();
        User masterUser = master.getUser();
        // Phase 242 — the ITEM's own salon snapshot (bookings.salon_id), NEVER master.getSalon().
        // Every item of a visit is stamped with the same salon at creation (AppointmentService/
        // GuestBookingService both write master.getSalon() onto the header AND each row), and a
        // per-item reschedule never rewrites it, so item 0's snapshot IS the visit's salon —
        // reading it off the already-fetched item costs nothing, whereas appointment.getSalon()
        // would lazy-load a proxy off the un-graphed appointmentRepository.findById.
        Salon salon = first.getSalon();

        // Same salon-vs-independent PII rule as BookingDetailResponse#from — the booked salon's own
        // values win outright when the visit was at a salon (even when null), so a salon-master's
        // personal address never leaks onto a salon visit.
        String resolvedStreet = salon != null ? salon.getStreet() : masterUser.getStreet();
        String resolvedBuildingNo = salon != null ? salon.getBuildingNo() : masterUser.getBuildingNo();
        String resolvedLocationNote = salon != null ? salon.getLocationNote() : masterUser.getLocationNote();

        // Per-service decline (DECLINED) and no-show (NOT_COMPLETED) lines are excluded from the
        // owed total and total duration — the client must not be shown a price/duration that
        // includes a service they will not receive (LOCKED decision). CONFIRMED/COMPLETED/CANCELLED
        // lines are summed as before; a fully-CONFIRMED visit is byte-for-byte unchanged. The header
        // time window (startsAt/endsAt below) deliberately still spans ALL items, INCLUDING excluded
        // (terminal) ones — "owed total" and "visit window" are independent concepts.
        BigDecimal totalPrice = BigDecimal.ZERO;
        BigDecimal totalCeiling = BigDecimal.ZERO;
        boolean anyRange = false;
        long totalDurationMinutesLong = 0L;
        for (Booking item : orderedItems) {
            if (isExcludedFromTotals(item.getStatus())) {
                continue;
            }
            totalPrice = totalPrice.add(item.getPriceAtBooking());
            if (item.getPriceMaxAtBooking() != null) {
                anyRange = true;
                totalCeiling = totalCeiling.add(item.getPriceMaxAtBooking());
            } else {
                totalCeiling = totalCeiling.add(item.getPriceAtBooking());
            }
            totalDurationMinutesLong += Duration.between(item.getStartsAt(), item.getEndsAt()).toMinutes();
        }
        int totalDurationMinutes = (int) totalDurationMinutesLong;

        List<AppointmentItemResponse> items = orderedItems.stream()
                .map(AppointmentItemResponse::from)
                .toList();

        // Header window spans ALL items (terminal included) — the intent stated above has always
        // been this, but orderedItems.get(size-1).getEndsAt() only expressed it correctly while
        // items were contiguous. Once a single item can be moved independently (phase 30.1), the row
        // with the greatest startsAt is no longer necessarily the row with the greatest endsAt: a
        // terminal sibling is exempt from every overlap guard and may span past the last CONFIRMED
        // item. min(startsAt) is still item 0 (the loader orders by startsAt ASC — see this method's
        // documented precondition above), so only the end needs an explicit reduction.
        OffsetDateTime headerEndsAt = orderedItems.stream()
                .map(Booking::getEndsAt)
                .max(Comparator.naturalOrder())
                .orElseThrow(); // orderedItems is non-empty by this method's documented precondition

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
                headerEndsAt.atZoneSameInstant(TimeZones.KYIV),
                totalDurationMinutes,
                totalPrice,
                anyRange ? totalCeiling : null,
                appointment.getClientComment(),
                appointment.getCreatedAt().atOffset(ZoneOffset.UTC),
                items,
                // Notes are read from the HEADER (mutually visible), never the child items.
                appointment.getProviderComment(),
                appointment.getClientCancellationNote(),
                cityLabel,
                districtLabel,
                resolvedStreet,
                resolvedBuildingNo,
                resolvedLocationNote);
    }

    /**
     * A service line is excluded from the owed {@code totalPrice}/{@code totalPriceMax} and from
     * {@code totalDurationMinutes} when it is {@code DECLINED} (per-service or whole-visit decline) or
     * {@code NOT_COMPLETED} (no-show) — the client does not owe for a service they will not receive.
     * {@code CONFIRMED}, {@code COMPLETED} and {@code CANCELLED} lines are still summed.
     */
    private static boolean isExcludedFromTotals(BookingStatus status) {
        return status == BookingStatus.DECLINED || status == BookingStatus.NOT_COMPLETED;
    }
}
