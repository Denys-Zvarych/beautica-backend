package com.beautica.master.dto;

import com.beautica.master.entity.WeekdayMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalTime;
import java.util.List;

/**
 * Phase 15.2 / 15.8: one ISO weekday of a {@link WeeklyScheduleRequest}.
 *
 * <p>{@code dayOfWeek} is ISO (1=Mon..7=Sun). A day is one of two modes:
 * <ul>
 *   <li>{@link WeekdayMode#INTERVAL} (default) — continuous window(s) carried in {@code intervals}; an
 *       empty {@code intervals} list means the master is off that weekday.</li>
 *   <li>{@link WeekdayMode#EXPLICIT_TIMES} (Phase 15.8) — a set of discrete bookable start times carried
 *       in {@code times}; these ARE the slot set, the displayed window is derived {@code [min..max]}.</li>
 * </ul>
 *
 * <p><b>Mode exclusivity</b> is enforced by {@link #isModeConsistent()}: a day carries EITHER intervals OR
 * times, never both. {@code mode == null} defaults to {@link WeekdayMode#INTERVAL} for backward
 * compatibility with pre-15.8 clients (the {@code times} field is additive/nullable).
 *
 * <p>The {@code @Size} caps bound the payload: {@code intervals} ≤ 6 (window-with-breaks),
 * {@code times} ≤ 24 (one discrete slot per hour of a day is already generous).
 */
public record WeeklyScheduleDayRequest(
        @Min(value = 1, message = "Day of week must be between 1 (Monday) and 7 (Sunday)")
        @Max(value = 7, message = "Day of week must be between 1 (Monday) and 7 (Sunday)")
        int dayOfWeek,

        // null = INTERVAL (default) for backward compatibility with pre-15.8 clients.
        WeekdayMode mode,

        @Valid
        @Size(max = 6, message = "A day may have at most 6 intervals")
        List<WorkIntervalDto> intervals,

        @Size(max = 24, message = "A day may have at most 24 discrete times")
        List<@NotNull(message = "A discrete time must not be null") LocalTime> times
) {

    /**
     * Pre-15.8 convenience constructor: an INTERVAL day with only intervals. Keeps the backward-compatible
     * call shape for callers (and the wire contract) that predate the {@code mode}/{@code times} fields.
     */
    public WeeklyScheduleDayRequest(int dayOfWeek, List<WorkIntervalDto> intervals) {
        this(dayOfWeek, WeekdayMode.INTERVAL, intervals, null);
    }

    /** The effective mode: an absent {@code mode} defaults to {@link WeekdayMode#INTERVAL}. */
    public WeekdayMode effectiveMode() {
        return mode != null ? mode : WeekdayMode.INTERVAL;
    }

    /**
     * Mode exclusivity: an INTERVAL day carries only intervals (possibly empty == day off) and no times;
     * an EXPLICIT_TIMES day carries non-empty times and no intervals (and therefore no breaks — breaks
     * are an interval-only, client-side affordance).
     */
    @AssertTrue(message =
            "INTERVAL days carry only intervals; EXPLICIT_TIMES days carry only a non-empty times list")
    public boolean isModeConsistent() {
        boolean hasIntervals = intervals != null && !intervals.isEmpty();
        boolean hasTimes = times != null && !times.isEmpty();
        return switch (effectiveMode()) {
            case INTERVAL -> !hasTimes;
            case EXPLICIT_TIMES -> hasTimes && !hasIntervals;
        };
    }
}
