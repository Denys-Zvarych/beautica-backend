package com.beautica.booking.dto;

import java.time.ZonedDateTime;

public record AvailableSlotResponse(ZonedDateTime startsAt, ZonedDateTime endsAt) {}
