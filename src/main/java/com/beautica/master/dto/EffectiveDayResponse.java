package com.beautica.master.dto;

import com.beautica.master.entity.ScheduleExceptionReason;

import java.time.LocalDate;
import java.util.List;

/**
 * Phase 15.2: the unambiguous effective-availability projection for a single calendar date, used by
 * the read-availability endpoint (Phase 15.5) that the mobile calendar paints.
 *
 * <p>The {@code source} disambiguates how the {@code intervals} were derived (template vs override vs
 * no schedule). {@code reason} is populated only when {@code source == OVERRIDE_DAY_OFF}; for a closed
 * or unscheduled date {@code intervals} is empty.
 *
 * <p><b>Wire format:</b> {@code date} is a {@link LocalDate}, serialized ISO-8601 as {@code yyyy-MM-dd}.
 * Computation of the effective projection lands in Phase 15.4–15.5.
 */
public record EffectiveDayResponse(
        LocalDate date,
        EffectiveDaySource source,
        List<WorkIntervalDto> intervals,
        ScheduleExceptionReason reason
) {
}
