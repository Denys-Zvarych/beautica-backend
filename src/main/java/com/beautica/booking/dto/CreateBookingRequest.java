package com.beautica.booking.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * @param startsAt validated with {@code @Future} using the default Bean Validation clock (system time).
 *   {@code BookingService} additionally enforces future-date bounds using the injected {@link java.time.Clock}
 *   bean from {@code ClockConfig}. The two clocks are intentionally independent — Spring's validator
 *   provides the first-pass 400, the service provides authoritative booking-window enforcement.
 * @param allowClientOverlap explicit, client-supplied opt-in (product decision 2026-08-22) to allow
 *   this booking to overlap the client's OWN other CONFIRMED booking(s). Defaults to {@code false}
 *   (a primitive {@code boolean}, so an absent field on the wire deserializes to {@code false} and
 *   every existing caller is byte-for-byte unaffected). When {@code false}, behaviour is unchanged:
 *   {@code BookingService#doCreateBooking} rejects a self-overlap with the usual
 *   {@code ClientBookingConflictException} (409). When {@code true}, ONLY that self-conflict check
 *   is skipped — the per-master {@code existsOverlap} check and the DB-level
 *   {@code no_overlapping_bookings} EXCLUDE constraint (which protect a DIFFERENT client's booking
 *   on the same master slot) still run exactly as before and can never be bypassed by this flag.
 *   It is the client's own responsibility to avoid a self-overlap they did not intend — see the
 *   locked product decision recorded on {@code BookingService#doCreateBooking}.
 */
public record CreateBookingRequest(
        @NotNull(message = "Master ID is required") UUID masterId,
        @NotNull(message = "Service ID is required") UUID masterServiceId,
        @NotNull(message = "Start time is required")
        @Future(message = "Start time must be in the future")
        ZonedDateTime startsAt,
        @Size(max = 64, message = "Idempotency key must be at most 64 characters")
        @Pattern(regexp = "^[A-Za-z0-9\\-_]{1,64}$", message = "Idempotency key must be 1–64 alphanumeric/dash/underscore characters")
        String idempotencyKey,
        // Control-char ban mirrors CancelBookingRequest.comment (§D): @Size alone lets embedded
        // NUL reach the DB and produce a 500 not a 400. Line
        // breaks (\n, \r) and tabs (\t) are permitted — this is a long-form free-text field.
        @Size(max = 1000, message = "Comment must be at most 1000 characters")
        @Pattern(regexp = "^[^\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]*$",
                message = "Comment must not contain control characters other than line breaks and tabs")
        String clientComment,
        boolean allowClientOverlap
) {}
