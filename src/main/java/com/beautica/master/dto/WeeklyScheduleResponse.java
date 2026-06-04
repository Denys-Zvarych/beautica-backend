package com.beautica.master.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Phase 15.2: read projection of a master's active-window weekly template.
 *
 * <p><b>Wire format:</b> {@code validFrom}/{@code validTo} are {@link LocalDate}, serialized ISO-8601
 * as {@code yyyy-MM-dd}. {@code validTo == null} means open-ended. Entity-to-DTO mapping lands in
 * Phase 15.4–15.5.
 */
public record WeeklyScheduleResponse(
        LocalDate validFrom,
        LocalDate validTo,
        List<WeeklyScheduleDayResponse> days
) {
}
