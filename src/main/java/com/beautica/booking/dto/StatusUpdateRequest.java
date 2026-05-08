package com.beautica.booking.dto;

import com.beautica.booking.enums.CancellationReason;
import jakarta.validation.constraints.Size;

public record StatusUpdateRequest(CancellationReason cancellationReason, @Size(max = 1000) String comment) {}
