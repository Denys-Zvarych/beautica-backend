package com.beautica.booking.dto;

import com.beautica.booking.service.SlotCalculationService;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Create-payload for a multi-service single-visit appointment (BE-3): ONE master, ONE chosen start
 * time, and N services performed back-to-back. Mirrors {@link CreateBookingRequest}'s validation
 * annotations field-for-field so the single-service and multi-service create paths reject malformed
 * input identically.
 *
 * @param masterServiceIds the ordered list of the master's {@code MasterService} ids to chain, in
 *   the exact order they will be performed. <b>Duplicates are allowed verbatim — the list is never
 *   de-duplicated</b>: the availability layer (BE-2) sizes the offered slot to the SUM of the list
 *   including repeats, so create must chain the same repeated services to stay consistent with what
 *   the client was shown. The block simply sums the repeated durations. Bounded by
 *   {@code @Size(max = }{@value SlotCalculationService#MAX_SERVICES_PER_VISIT}{@code )} — the same
 *   cap BE-2 enforces on the slot/day endpoints — so an unbounded id list can never reach the
 *   per-id master-service lookups.
 * @param startsAt the visit start (= the first service's start); each subsequent service starts when
 *   the previous one ends (duration + its own {@code bufferMinutesAfter}, D4). Typed as
 *   {@link ZonedDateTime} exactly like {@link CreateBookingRequest#startsAt()}. Validated with
 *   {@code @Future} using the default Bean Validation clock; {@code AppointmentService} additionally
 *   enforces the authoritative lead-time / max-window bounds via the injected {@link java.time.Clock}.
 * @param idempotencyKey optional client-supplied dedup key. {@code @Pattern} (not merely
 *   {@code @Size}) so {@code " a"} and {@code "a"} map to the same bucket (§L).
 * @param clientComment optional booking-creation note for the whole visit — same {@code @Size} +
 *   control-char ban as {@link CreateBookingRequest#clientComment()}.
 */
public record CreateAppointmentRequest(
        @NotNull(message = "Master ID is required") UUID masterId,

        @NotEmpty(message = "At least one service is required")
        @Size(max = SlotCalculationService.MAX_SERVICES_PER_VISIT,
                message = "At most 10 services can be booked in a single visit")
        List<@NotNull(message = "Service ID must not be null") UUID> masterServiceIds,

        @NotNull(message = "Start time is required")
        @Future(message = "Start time must be in the future")
        ZonedDateTime startsAt,

        @Size(max = 64, message = "Idempotency key must be at most 64 characters")
        @Pattern(regexp = "^[A-Za-z0-9\\-_]{1,64}$",
                message = "Idempotency key must be 1–64 alphanumeric/dash/underscore characters")
        String idempotencyKey,

        // Control-char ban mirrors CreateBookingRequest.clientComment (§D): @Size alone lets an
        // embedded NUL reach the DB and produce a 500 not a 400. Line breaks (\n, \r) and tabs (\t)
        // are permitted — this is a long-form free-text field.
        @Size(max = 1000, message = "Comment must be at most 1000 characters")
        @Pattern(regexp = "^[^\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]*$",
                message = "Comment must not contain control characters other than line breaks and tabs")
        String clientComment
) {}
