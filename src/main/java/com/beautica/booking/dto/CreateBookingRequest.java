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
 */
public record CreateBookingRequest(
        @NotNull(message = "Master ID is required") UUID masterId,
        @NotNull(message = "Service ID is required") UUID masterServiceId,
        @NotNull(message = "Start time is required")
        @Future(message = "Start time must be in the future")
        ZonedDateTime startsAt,
        @Size(max = 64, message = "Idempotency key must be at most 64 characters")
        @Pattern(regexp = "[A-Za-z0-9\\-_]{1,64}", message = "Idempotency key must be 1–64 alphanumeric/dash/underscore characters")
        String idempotencyKey,
        // Control-char ban mirrors CancelBookingRequest.comment and ScheduleExceptionRequest.note
        // (§D): @Size alone lets embedded NUL reach the DB and produce a 500 not a 400. Line
        // breaks (\n, \r) and tabs (\t) are permitted — this is a long-form free-text field.
        @Size(max = 1000, message = "Comment must be at most 1000 characters")
        @Pattern(regexp = "^[^\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]*$",
                message = "Comment must not contain control characters other than line breaks and tabs")
        String clientComment
) {}
