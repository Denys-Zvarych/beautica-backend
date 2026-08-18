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
 *
 * <p><b>Phase 15.12 — {@code windowStart}/{@code windowEnd} (optional, display-only).</b> The editor models
 * a day as ONE window (від–до) with breaks carved out, but only the resulting {@code intervals} are stored,
 * and breaks are rebuilt from the GAPS BETWEEN them. A break flush against an edge of the window leaves no
 * gap (09:00–18:00 minus a 09:00–10:00 break == the single interval {@code [10:00–18:00]}), so it vanishes
 * on reload. Sending the window lets the client derive breaks as {@code window MINUS intervals} and recover
 * edge-flush ones. The bounds are <b>metadata only</b>: {@code intervals} remains the single canonical
 * source of availability and the slot/booking engine never reads the window. Both fields null (the legacy
 * shape — behaves exactly as before) or both set; see {@link #isWindowConsistent()}. Meaningful only for an
 * {@link WeekdayMode#INTERVAL} day with ≥1 interval — on a day off or an {@code EXPLICIT_TIMES} day the
 * service persists {@code null} and ignores whatever was supplied.
 */
public record WeeklyScheduleDayRequest(
        @Min(value = 1, message = "Day of week must be between 1 (Monday) and 7 (Sunday)")
        @Max(value = 7, message = "Day of week must be between 1 (Monday) and 7 (Sunday)")
        int dayOfWeek,

        // null = INTERVAL (default) for backward compatibility with pre-15.8 clients.
        WeekdayMode mode,

        // The element-level @NotNull is load-bearing, not decoration: Hibernate Validator's @Valid cascade
        // SKIPS null elements, so without it `{"intervals":[null]}` passes Bean Validation and NPEs in the
        // service (assertIntervalsNonOverlapping / resolveWindow dereference each dto) — a 500 where the
        // caller deserves a 400. Mirrors the sibling `times` list, which has always declared it.
        @Valid
        @Size(max = 6, message = "A day may have at most 6 intervals")
        List<@NotNull(message = "An interval must not be null") WorkIntervalDto> intervals,

        @Size(max = 24, message = "A day may have at most 24 discrete times")
        List<@NotNull(message = "A discrete time must not be null") LocalTime> times,

        // Phase 15.12 — both null (legacy/omitted) or both set. Display-only; never widens/narrows slots.
        LocalTime windowStart,

        LocalTime windowEnd
) {

    /**
     * Pre-15.8 convenience constructor: an INTERVAL day with only intervals. Keeps the backward-compatible
     * call shape for callers (and the wire contract) that predate the {@code mode}/{@code times} fields.
     */
    public WeeklyScheduleDayRequest(int dayOfWeek, List<WorkIntervalDto> intervals) {
        this(dayOfWeek, WeekdayMode.INTERVAL, intervals, null, null, null);
    }

    /**
     * Pre-15.12 convenience constructor: the {@code mode}/{@code intervals}/{@code times} shape that predates
     * the display-only working-window bounds. Keeps every existing caller compiling unchanged with a
     * {@code null} window (i.e. verbatim pre-15.12 behaviour).
     */
    public WeeklyScheduleDayRequest(
            int dayOfWeek, WeekdayMode mode, List<WorkIntervalDto> intervals, List<LocalTime> times) {
        this(dayOfWeek, mode, intervals, times, null, null);
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

    /**
     * Phase 15.12: shape of the optional working-window bounds — both omitted (legacy/absent) or both
     * present, and strictly ordered when present (no zero-length, no midnight crossing, matching the locked
     * Phase 15.x no-cross-midnight interval contract).
     *
     * <p>Only the SHAPE is checked here. <b>Containment</b> — the window must contain every interval of the
     * day — needs the interval list AND the resolved mode, so it lives in {@code MasterScheduleService}
     * alongside the intra-day overlap rule (and is mirrored there defensively, as every other rule in this
     * record is).
     */
    @AssertTrue(message =
            "windowStart and windowEnd must both be provided or both omitted, and windowEnd must be "
                    + "after windowStart")
    public boolean isWindowConsistent() {
        return (windowStart == null) == (windowEnd == null)
                && (windowStart == null || windowEnd.isAfter(windowStart));
    }
}
