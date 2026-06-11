package com.beautica.master.dto;

import com.beautica.master.entity.ScheduleExceptionKind;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/**
 * Phase 15.2: a per-date override of the weekly template — one row per calendar date.
 *
 * <p>The {@code kind} discriminator selects the consistent field set:
 * <ul>
 *   <li>{@link ScheduleExceptionKind#DAY_OFF} — closure; {@code intervals} empty. A day-off carries
 *       no reason and no note (both removed in V83).</li>
 *   <li>{@link ScheduleExceptionKind#CUSTOM_HOURS} — different hours; {@code intervals} non-empty.</li>
 * </ul>
 *
 * <p><b>Wire format:</b> {@code date} is a {@link LocalDate}, serialized ISO-8601 as {@code yyyy-MM-dd}.
 * Multi-day spans are expanded client-side into N single-date rows on save.
 *
 * <p><b>Deferred to Phase 15.4 (service layer):</b> the authoritative "no past edits" rule against the
 * injected Kyiv {@code Clock} ({@link FutureOrPresent} is request-time convenience only and uses the
 * server default zone); non-overlap of {@code intervals} within the date.
 */
public record ScheduleOverrideRequest(
        @NotNull(message = "Date is required")
        @FutureOrPresent(message = "Date must be today or in the future")
        LocalDate date,

        @NotNull(message = "Kind is required")
        ScheduleExceptionKind kind,

        // Required iff CUSTOM_HOURS.
        @Valid
        @Size(max = 6, message = "An override may have at most 6 intervals")
        List<WorkIntervalDto> intervals
) {

    @AssertTrue(message = "DAY_OFF must have no intervals; CUSTOM_HOURS requires intervals")
    public boolean isKindConsistent() {
        if (kind == null) {
            return true; // @NotNull on kind reports the missing-kind error.
        }
        boolean hasIntervals = intervals != null && !intervals.isEmpty();
        return switch (kind) {
            case DAY_OFF -> !hasIntervals;
            case CUSTOM_HOURS -> hasIntervals;
        };
    }
}
