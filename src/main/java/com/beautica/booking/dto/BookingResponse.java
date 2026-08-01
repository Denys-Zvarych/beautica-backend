package com.beautica.booking.dto;

import com.beautica.booking.domain.BookingClosureRule;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.common.TimeZones;
import io.swagger.v3.oas.annotations.media.Schema;
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
 * to avoid N+1 lazy loads. {@code priceMaxAtBooking} does NOT widen that requirement — it is a
 * frozen column on the booking row itself (V119), not a walk into the service definition.
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
        @Schema(types = {"number", "null"}, nullable = true,
                description = "The range ceiling agreed AT BOOKING TIME, present ONLY when the "
                        + "master left this service's price as a genuine RANGE (no priceOverride) "
                        + "when the booking was made. Null means a single price — render "
                        + "priceAtBooking alone. The client must never re-derive this from "
                        + "priceType/priceOverride; the decision is made server-side, once.")
        BigDecimal priceMaxAtBooking,
        int durationMinutesAtBooking,
        // priceAtBooking, priceMaxAtBooking and durationMinutesAtBooking are all snapshot columns
        // on the bookings row — they reflect the price band/duration the client agreed to, and are
        // never re-derived from current master_services/service_definitions values.
        OffsetDateTime createdAt,
        @Schema(types = {"string", "null"}, format = "uuid", nullable = true,
                description = "The multi-service visit (BE-5) this booking belongs to, or null for a "
                        + "legacy single-service booking (appointment_id IS NULL). When non-null, "
                        + "N booking rows sharing this id are ONE client-facing visit — the mobile My "
                        + "Bookings list collapses them into a single card and fetches the full visit "
                        + "via GET /appointments/{appointmentId}. A client that ignores this field is "
                        + "unaffected (strictly additive).")
        UUID appointmentId,
        @Schema(description = "Derived, read-time-only (Phase 29.1/29.2) — TRUE when this "
                + "booking's status is still CONFIRMED but its endsAt has already elapsed: no "
                + "scheduled job ever transitions such a booking to a terminal state, so this "
                + "flags the ones the provider still needs to close via /complete, "
                + "/not-complete or /decline. NEVER persisted, NEVER cached — recomputed on "
                + "every read from (status, endsAt, the current instant). Orthogonal to any "
                + "review-eligibility field on the enriched detail DTO: an elapsed CONFIRMED "
                + "booking is never itself review-eligible.")
        boolean awaitingClosure
) {
    public static BookingResponse from(Booking booking, OffsetDateTime now) {
        return new BookingResponse(
                booking.getId(),
                // Guest (LINK) bookings have no registered client (V89 chk_bookings_guest_fields) —
                // clientId is null for them; only account-bound (APP) bookings carry a client UUID.
                booking.getClient() != null ? booking.getClient().getId() : null,
                booking.getMaster().getId(),
                booking.getMasterService().getId(),
                booking.getMasterService().getServiceDefinition().getName(),
                booking.getStatus(),
                booking.getStartsAt().atZoneSameInstant(TimeZones.KYIV),
                booking.getEndsAt().atZoneSameInstant(TimeZones.KYIV),
                booking.getPriceAtBooking(),
                booking.getPriceMaxAtBooking(),
                booking.getDurationMinutesAtBooking(),
                booking.getCreatedAt().atOffset(ZoneOffset.UTC),
                // appointment is a LAZY @ManyToOne on a nullable FK — Hibernate serves the
                // identifier off the proxy (or resolves it as null) from the booking row itself,
                // with no extra SELECT and no widening of any caller's fetch graph.
                booking.getAppointment() != null ? booking.getAppointment().getId() : null,
                BookingClosureRule.isAwaitingClosure(booking.getStatus(), booking.getEndsAt(), now)
        );
    }
}
