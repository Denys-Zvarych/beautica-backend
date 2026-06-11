package com.beautica.master.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Phase 15.2: the unambiguous effective-availability projection for a single calendar date, used by
 * the read-availability endpoint (Phase 15.5) that the mobile calendar paints.
 *
 * <p>The {@code source} disambiguates how the {@code intervals} were derived (template vs override vs
 * no schedule). For a closed or unscheduled date {@code intervals} is empty. A day-off carries no
 * reason (the {@code reason} field was removed in V83).
 *
 * <p><b>Wire format:</b> {@code date} is a {@link LocalDate}, serialized ISO-8601 as {@code yyyy-MM-dd}.
 */
public record EffectiveDayResponse(
        LocalDate date,
        EffectiveDaySource source,
        List<WorkIntervalDto> intervals
) {
}
