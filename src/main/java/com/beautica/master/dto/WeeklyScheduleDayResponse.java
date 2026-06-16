package com.beautica.master.dto;

import com.beautica.master.entity.WeekdayMode;

import java.time.LocalTime;
import java.util.List;

/**
 * Phase 15.2 / 15.8: one ISO weekday in a {@link WeeklyScheduleResponse}.
 *
 * <p>{@code dayOfWeek} is ISO (1=Mon..7=Sun). {@code mode} is derived from storage (Phase 15.8): a day with
 * ≥1 discrete-time row is {@link WeekdayMode#EXPLICIT_TIMES} and carries its sorted {@code times}; otherwise
 * it is {@link WeekdayMode#INTERVAL} and carries its sorted {@code intervals} (an empty interval list means
 * a day off). For an {@code INTERVAL} day {@code times} is empty; for an {@code EXPLICIT_TIMES} day
 * {@code intervals} is empty. Both list fields are non-null (possibly empty) for a stable wire shape.
 */
public record WeeklyScheduleDayResponse(
        int dayOfWeek,
        WeekdayMode mode,
        List<WorkIntervalDto> intervals,
        List<LocalTime> times
) {
}
