package com.beautica.master.dto;

import com.beautica.master.entity.ScheduleExceptionKind;

import java.time.LocalDate;
import java.util.List;

/**
 * Phase 15.2: read projection of a per-date override.
 *
 * <p>{@code intervals} is populated only for {@link ScheduleExceptionKind#CUSTOM_HOURS}; a
 * {@link ScheduleExceptionKind#DAY_OFF} carries no intervals (and, since V83, no reason or note).
 *
 * <p><b>Wire format:</b> {@code date} is a {@link LocalDate}, serialized ISO-8601 as {@code yyyy-MM-dd}.
 */
public record ScheduleOverrideResponse(
        LocalDate date,
        ScheduleExceptionKind kind,
        List<WorkIntervalDto> intervals
) {
}
