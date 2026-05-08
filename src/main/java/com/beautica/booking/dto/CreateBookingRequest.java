package com.beautica.booking.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.ZonedDateTime;
import java.util.UUID;

public record CreateBookingRequest(
        @NotNull UUID masterId,
        @NotNull UUID masterServiceId,
        @NotNull @Future ZonedDateTime startsAt,
        @Size(max = 64) String idempotencyKey
) {}
