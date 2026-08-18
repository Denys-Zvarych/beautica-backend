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
 *
 * <p><b>Phase 15.12 — {@code windowStart}/{@code windowEnd} (nullable, display-only).</b> Echoes back the
 * working-window bounds the client recorded for an {@link WeekdayMode#INTERVAL} day, so the editor can
 * derive breaks as {@code window MINUS intervals} and recover a break that sits flush against an edge of the
 * window (which leaves no gap between intervals and would otherwise disappear on reload). {@code null} on
 * every legacy row, on a day off, and on an {@code EXPLICIT_TIMES} day — the client then falls back to
 * gap reconstruction, i.e. exactly the pre-15.12 behaviour. These bounds are metadata: {@code intervals}
 * remains the single canonical source of availability and no server-side slot computation reads them.
 */
public record WeeklyScheduleDayResponse(
        int dayOfWeek,
        WeekdayMode mode,
        List<WorkIntervalDto> intervals,
        List<LocalTime> times,
        LocalTime windowStart,
        LocalTime windowEnd
) {

    /**
     * Pre-15.12 convenience constructor: a day carrying no recorded working window. Keeps the
     * backward-compatible call shape for callers that predate the {@code windowStart}/{@code windowEnd}
     * fields.
     */
    public WeeklyScheduleDayResponse(
            int dayOfWeek, WeekdayMode mode, List<WorkIntervalDto> intervals, List<LocalTime> times) {
        this(dayOfWeek, mode, intervals, times, null, null);
    }
}
