package com.beautica.master.dto;

import com.beautica.master.entity.ScheduleExceptionKind;
import com.beautica.master.entity.ScheduleExceptionReason;

import java.time.LocalDate;
import java.util.List;

/**
 * Phase 15.2: read projection of a per-date override.
 *
 * <p>{@code reason} is populated only for {@link ScheduleExceptionKind#DAY_OFF}; {@code intervals}
 * is populated only for {@link ScheduleExceptionKind#CUSTOM_HOURS}.
 *
 * <p><b>Wire format:</b> {@code date} is a {@link LocalDate}, serialized ISO-8601 as {@code yyyy-MM-dd}.
 * Entity-to-DTO mapping lands in Phase 15.4–15.5.
 */
public record ScheduleOverrideResponse(
        LocalDate date,
        ScheduleExceptionKind kind,
        ScheduleExceptionReason reason,
        String note,
        List<WorkIntervalDto> intervals
) {
}
