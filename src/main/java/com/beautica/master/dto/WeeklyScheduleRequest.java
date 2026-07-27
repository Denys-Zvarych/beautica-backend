package com.beautica.master.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

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

        // The element-level @NotNull is load-bearing: Hibernate Validator's @Valid cascade SKIPS null
        // elements, so without it `{"days":[null]}` reaches isDaysUnique() below — which dereferences
        // every element — and NPEs INSIDE the @AssertTrue getter. Hibernate Validator wraps that in a
        // ValidationException, i.e. a 500 raised before any handler can render a 400.
        @Valid
        @Size(max = 7, message = "A weekly template may have at most 7 days")
        List<@NotNull(message = "A day must not be null") WeeklyScheduleDayRequest> days
) {

    @AssertTrue(message = "validTo must be on or after validFrom")
    public boolean isWindowOrdered() {
        return validTo == null || validFrom == null || !validTo.isBefore(validFrom);
    }

    /**
     * Uniqueness of {@code dayOfWeek} across the supplied days.
     *
     * <p>Null elements are filtered out rather than dereferenced. The element-level {@code @NotNull} on
     * {@code days} is what reports them; this getter must not be the thing that notices, because an
     * {@code @AssertTrue} getter runs REGARDLESS of sibling constraint outcomes or ordering — an NPE
     * raised in here escapes as a {@code ValidationException} (500) before {@code GlobalExceptionHandler}
     * can render the 400 the element constraint already produced. Filtering keeps each constraint
     * reporting exactly its own concern.
     */
    @AssertTrue(message = "dayOfWeek values must be unique")
    public boolean isDaysUnique() {
        if (days == null) {
            return true;
        }
        List<WeeklyScheduleDayRequest> present = days.stream().filter(Objects::nonNull).toList();
        return present.stream().map(WeeklyScheduleDayRequest::dayOfWeek).distinct().count() == present.size();
    }
}
