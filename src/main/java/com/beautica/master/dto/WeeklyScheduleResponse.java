package com.beautica.master.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Phase 15.2: read projection of a master's active-window weekly template.
 *
 * <p><b>Wire format:</b> {@code id} is the persisted template's UUID — non-null for read projections
 * — and is the identity clients use to target {@code PUT /weekly-schedules/{scheduleId}} when updating
 * an existing window (without it, editors re-POST and trip the overlap guard). {@code validFrom}/{@code
 * validTo} are {@link LocalDate}, serialized ISO-8601 as {@code yyyy-MM-dd}. {@code validTo == null}
 * means open-ended.
 */
public record WeeklyScheduleResponse(
        UUID id,
        LocalDate validFrom,
        LocalDate validTo,
        List<WeeklyScheduleDayResponse> days
) {
}
