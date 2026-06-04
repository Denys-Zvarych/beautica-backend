package com.beautica.master.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Phase 15.2: one ISO weekday of a {@link WeeklyScheduleRequest}.
 *
 * <p>{@code dayOfWeek} is ISO (1=Mon..7=Sun). An empty {@code intervals} list means the master is
 * off that weekday. The {@code @Size(max = 6)} cap bounds the payload (the preview's
 * window-with-breaks produces only a handful of intervals).
 */
public record WeeklyScheduleDayRequest(
        @Min(value = 1, message = "Day of week must be between 1 (Monday) and 7 (Sunday)")
        @Max(value = 7, message = "Day of week must be between 1 (Monday) and 7 (Sunday)")
        int dayOfWeek,

        @Valid
        @Size(max = 6, message = "A day may have at most 6 intervals")
        List<WorkIntervalDto> intervals
) {
}
