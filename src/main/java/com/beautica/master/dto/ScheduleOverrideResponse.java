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
 * <p><b>Wire format:</b> {@code date} is a {@link LocalDate}, serialized ISO-8601 as {@code yyyy-MM-dd};
 * each {@code times} entry is a {@link LocalTime}, serialized ISO-8601 as {@code HH:mm:ss}.
 */
public record ScheduleOverrideResponse(
        LocalDate date,
        ScheduleExceptionKind kind,
        WeekdayMode mode,
        List<WorkIntervalDto> intervals,
        List<LocalTime> times
) {

    /**
     * Pre-15.9 convenience constructor: an INTERVAL override (or DAY_OFF) carrying only {@code intervals},
     * no discrete times. Keeps the backward-compatible call shape for callers that predate the
     * {@code mode}/{@code times} fields.
     */
    public ScheduleOverrideResponse(LocalDate date, ScheduleExceptionKind kind, List<WorkIntervalDto> intervals) {
        this(date, kind, WeekdayMode.INTERVAL, intervals, List.of());
    }
}
