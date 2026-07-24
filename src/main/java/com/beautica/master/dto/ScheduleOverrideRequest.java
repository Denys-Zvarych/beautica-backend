package com.beautica.master.dto;

import com.beautica.master.entity.ScheduleExceptionKind;
import com.beautica.master.entity.WeekdayMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Phase 15.2 / 15.9: a per-date override of the weekly template — one row per calendar date.
 *
 * <p>The {@code kind} discriminator selects the consistent field set:
 * <ul>
 *   <li>{@link ScheduleExceptionKind#DAY_OFF} — closure; neither {@code intervals} nor {@code times}.
 *       A day-off carries no reason and no note (both removed in V83).</li>
 *   <li>{@link ScheduleExceptionKind#CUSTOM_HOURS} — different hours; one of two modes
 *       (Phase 15.9), exactly mirroring {@link WeeklyScheduleDayRequest}:
 *       <ul>
 *         <li>{@link WeekdayMode#INTERVAL} (default) — continuous window(s) carried in {@code intervals}.</li>
 *         <li>{@link WeekdayMode#EXPLICIT_TIMES} — a set of discrete bookable start times carried in
 *             {@code times}; these ARE the slot set, the displayed window is derived {@code [min..max]}.</li>
 *       </ul></li>
 * </ul>
 *
 * <p><b>Mode exclusivity</b> is enforced by {@link #isKindConsistent()}: a {@code CUSTOM_HOURS} override
 * carries EITHER intervals OR times, never both; a {@code DAY_OFF} carries neither. {@code mode == null}
 * defaults to {@link WeekdayMode#INTERVAL} for backward compatibility with pre-15.9 clients (the
 * {@code mode}/{@code times} fields are additive/nullable).
 *
 * <p><b>Wire format:</b> {@code date} is a {@link LocalDate}, serialized ISO-8601 as {@code yyyy-MM-dd};
 * each {@code times} entry is a {@link LocalTime}, serialized ISO-8601 as {@code HH:mm:ss}. Multi-day spans
 * are expanded client-side into N single-date rows on save.
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

        // null = INTERVAL (default) for backward compatibility with pre-15.9 clients.
        WeekdayMode mode,

        // Required iff CUSTOM_HOURS + INTERVAL mode.
        @Valid
        @Size(max = 6, message = "An override may have at most 6 intervals")
        List<WorkIntervalDto> intervals,

        // Required iff CUSTOM_HOURS + EXPLICIT_TIMES mode (Phase 15.9).
        @Size(max = 24, message = "An override may have at most 24 discrete times")
        List<@NotNull(message = "A discrete time must not be null") LocalTime> times
) {

    /**
     * Pre-15.9 convenience constructor: an INTERVAL override (or DAY_OFF) with only intervals. Keeps the
     * backward-compatible call shape for callers (and the wire contract) that predate {@code mode}/{@code times}.
     */
    public ScheduleOverrideRequest(LocalDate date, ScheduleExceptionKind kind, List<WorkIntervalDto> intervals) {
        this(date, kind, WeekdayMode.INTERVAL, intervals, null);
    }

    /** The effective mode: an absent {@code mode} defaults to {@link WeekdayMode#INTERVAL}. */
    public WeekdayMode effectiveMode() {
        return mode != null ? mode : WeekdayMode.INTERVAL;
    }

    @AssertTrue(message =
            "DAY_OFF carries no intervals or times; CUSTOM_HOURS carries either intervals (INTERVAL) "
                    + "or a non-empty times list (EXPLICIT_TIMES), never both")
    public boolean isKindConsistent() {
        if (kind == null) {
            return true; // @NotNull on kind reports the missing-kind error.
        }
        boolean hasIntervals = intervals != null && !intervals.isEmpty();
        boolean hasTimes = times != null && !times.isEmpty();
        return switch (kind) {
            case DAY_OFF -> !hasIntervals && !hasTimes;
            case CUSTOM_HOURS -> switch (effectiveMode()) {
                case INTERVAL -> hasIntervals && !hasTimes;
                case EXPLICIT_TIMES -> hasTimes && !hasIntervals;
            };
        };
    }
}
