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
        @NotNull UUID masterId,
        @NotNull UUID masterServiceId,
        @NotNull @Future ZonedDateTime startsAt,
        @Size(max = 64)
        @Pattern(regexp = "[A-Za-z0-9\\-_]{1,64}", message = "Idempotency key must be 1–64 alphanumeric/dash/underscore characters")
        String idempotencyKey,
        @Size(max = 1000) String clientComment
) {}
