package com.beautica.master.dto;

import java.util.List;

/**
 * Phase 15.2: one ISO weekday in a {@link WeeklyScheduleResponse}.
 *
 * <p>{@code dayOfWeek} is ISO (1=Mon..7=Sun); an empty {@code intervals} list means a day off.
 */
public record WeeklyScheduleDayResponse(
        int dayOfWeek,
        List<WorkIntervalDto> intervals
) {
}
