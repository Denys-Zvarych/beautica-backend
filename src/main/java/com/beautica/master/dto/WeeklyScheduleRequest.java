package com.beautica.master.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/**
 * Phase 15.2: create/replace request for a master's active-window weekly template.
 *
 * <p>The wire shape mirrors the approved mobile preview: one row per ISO weekday carrying an ordered
 * list of intervals. The backend never models "breaks" — only intervals.
 *
 * <p><b>Wire format:</b> {@code validFrom}/{@code validTo} are {@link LocalDate}, serialized ISO-8601
 * as {@code yyyy-MM-dd} via the default Jackson {@code JavaTimeModule}. {@code validTo == null} means
 * open-ended (subject to the service-layer horizon cap, Phase 15.4).
 *
 * <p><b>Deferred to Phase 15.4 (service layer):</b>
 * <ul>
 *   <li>The authoritative "no past edits" rule against the injected Kyiv {@code Clock} —
 *       {@link FutureOrPresent} here is request-time convenience only and uses the server default zone.</li>
 *   <li>The open-ended {@code validTo} horizon cap.</li>
 *   <li>Per-master schedule-window overlap.</li>
 *   <li>Non-overlap of intervals within the same weekday.</li>
 * </ul>
 */
public record WeeklyScheduleRequest(
        @NotNull(message = "validFrom is required")
        @FutureOrPresent(message = "validFrom must be today or in the future")
        LocalDate validFrom,

        // null = open-ended (subject to the service-layer horizon cap).
        LocalDate validTo,

        @Valid
        @Size(max = 7, message = "A weekly template may have at most 7 days")
        List<WeeklyScheduleDayRequest> days
) {

    @AssertTrue(message = "validTo must be on or after validFrom")
    public boolean isWindowOrdered() {
        return validTo == null || validFrom == null || !validTo.isBefore(validFrom);
    }

    @AssertTrue(message = "dayOfWeek values must be unique")
    public boolean isDaysUnique() {
        if (days == null) {
            return true;
        }
        return days.stream().map(WeeklyScheduleDayRequest::dayOfWeek).distinct().count() == days.size();
    }
}
