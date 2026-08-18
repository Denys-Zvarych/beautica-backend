package com.beautica.master.dto;

import com.beautica.master.entity.ScheduleExceptionKind;
import com.beautica.master.entity.WeekdayMode;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Phase 15.2 / 15.9: read projection of a per-date override.
 *
 * <p>{@code intervals} / {@code times} are populated only for {@link ScheduleExceptionKind#CUSTOM_HOURS}; a
 * {@link ScheduleExceptionKind#DAY_OFF} carries neither (and, since V83, no reason or note).
 *
 * <p>Phase 15.9: {@code mode} disambiguates a custom-hours override. {@link WeekdayMode#INTERVAL} carries
 * sorted {@code intervals} (empty {@code times}); {@link WeekdayMode#EXPLICIT_TIMES} carries sorted
 * {@code times} (empty {@code intervals}). A {@code DAY_OFF} reports {@link WeekdayMode#INTERVAL} with both
 * lists empty. The {@code mode}/{@code times} fields are additive/nullable so the contract stays
 * backward-compatible.
 *
 * <p><b>Phase 15.12 — {@code windowStart}/{@code windowEnd} (nullable, display-only).</b> Echoes back the
 * working-window bounds the client recorded for an {@link WeekdayMode#INTERVAL} {@code CUSTOM_HOURS}
 * override, so the editor can derive breaks as {@code window MINUS intervals} and recover a break that sits
 * flush against an edge of the window (which leaves no gap between intervals and would otherwise disappear
 * on reload). {@code null} on every legacy row, on a {@code DAY_OFF}, and on an {@code EXPLICIT_TIMES}
 * override — the client then falls back to gap reconstruction, i.e. exactly the pre-15.12 behaviour. These
 * bounds are metadata: {@code intervals} remains the single canonical source of availability and no
 * server-side slot computation reads them.
 *
 * <p><b>Wire format:</b> {@code date} is a {@link LocalDate}, serialized ISO-8601 as {@code yyyy-MM-dd};
 * each {@code times} entry is a {@link LocalTime}, serialized ISO-8601 as {@code HH:mm:ss}.
 */
public record ScheduleOverrideResponse(
        LocalDate date,
        ScheduleExceptionKind kind,
        WeekdayMode mode,
        List<WorkIntervalDto> intervals,
        List<LocalTime> times,
        LocalTime windowStart,
        LocalTime windowEnd
) {

    /**
     * Pre-15.9 convenience constructor: an INTERVAL override (or DAY_OFF) carrying only {@code intervals},
     * no discrete times. Keeps the backward-compatible call shape for callers that predate the
     * {@code mode}/{@code times} fields. No working window is recorded.
     */
    public ScheduleOverrideResponse(LocalDate date, ScheduleExceptionKind kind, List<WorkIntervalDto> intervals) {
        this(date, kind, WeekdayMode.INTERVAL, intervals, List.of(), null, null);
    }

    /**
     * Pre-15.12 convenience constructor: the {@code mode}/{@code intervals}/{@code times} shape that predates
     * the display-only working-window bounds. Keeps every existing caller compiling unchanged with a
     * {@code null} window (i.e. verbatim pre-15.12 behaviour).
     */
    public ScheduleOverrideResponse(
            LocalDate date, ScheduleExceptionKind kind, WeekdayMode mode,
            List<WorkIntervalDto> intervals, List<LocalTime> times) {
        this(date, kind, mode, intervals, times, null, null);
    }
}
