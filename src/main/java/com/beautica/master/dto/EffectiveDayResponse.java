package com.beautica.master.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Phase 15.2 / 15.8: the unambiguous effective-availability projection for a single calendar date, used by
 * the read-availability endpoint (Phase 15.5) that the mobile calendar paints.
 *
 * <p>The {@code source} disambiguates how the {@code intervals} were derived (template vs override vs
 * no schedule). For a closed or unscheduled date {@code intervals} is empty. A day-off carries no
 * reason (the {@code reason} field was removed in V83).
 *
 * <p><b>Phase 15.8 (additive, nullable).</b> {@code times} carries the discrete bookable start times for an
 * {@code EXPLICIT_TIMES} weekday so the mobile calendar can paint slot chips. It is {@code null} for every
 * other day (INTERVAL template, override, or no schedule) — keeping the field additive and the contract
 * backward-compatible. For an EXPLICIT_TIMES day {@code intervals} still carries the derived window
 * {@code [min(times)..max(times)]} as a single interval, so window-only consumers keep working unchanged.
 *
 * <p><b>Phase 15.12 (additive, nullable) — {@code windowStart}/{@code windowEnd}.</b> The display-only
 * working-window bounds of whichever source produced {@code intervals}, so the mobile day-override editor
 * (which hydrates from THIS projection, not from {@code GET /overrides}) can derive breaks as
 * {@code window MINUS intervals} and recover a break sitting flush against an edge of the working day. Such
 * a break leaves no gap between intervals — window 09:00–18:00 minus a 09:00–10:00 break stores as the
 * single interval {@code [10:00–18:00]} — so gap reconstruction alone silently loses it.
 *
 * <p>The window always describes the same source as the {@code intervals} beside it:
 * <ul>
 *   <li>{@link EffectiveDaySource#TEMPLATE} — the template weekday's stored window
 *       ({@code weekly_schedule_day_windows}).</li>
 *   <li>{@link EffectiveDaySource#OVERRIDE_CUSTOM} — the override row's
 *       {@code window_start}/{@code window_end}.</li>
 *   <li>{@link EffectiveDaySource#OVERRIDE_DAY_OFF}, {@link EffectiveDaySource#NO_SCHEDULE}, any day with
 *       no intervals, and every {@code EXPLICIT_TIMES} day — {@code null}.</li>
 *   <li>Any resolved source that stored no window (every legacy row) — {@code null}; the client falls back
 *       to today's gap reconstruction, i.e. exactly the pre-15.12 behaviour. A window is NEVER synthesized
 *       from {@code min(start)..max(end)}, which would falsely assert "this day has no edge-flush break".</li>
 * </ul>
 *
 * <p><b>Read-only metadata.</b> These bounds carry no availability meaning and reach no write path — this
 * is a response record with no request counterpart. {@code intervals} remains the single canonical source
 * of bookable time and {@code SlotCalculationService} reads {@link #intervals()} alone, so a projected
 * window can neither widen nor narrow a computed slot (pinned by
 * {@code SlotCalculationScheduleIT#should_produceIdenticalSlots_when_effectiveDayCarriesWorkingWindow}).
 *
 * <p><b>Wire format:</b> {@code date} is a {@link LocalDate}, serialized ISO-8601 as {@code yyyy-MM-dd};
 * each {@code times} entry, and both window bounds, are {@link LocalTime}, serialized ISO-8601 as
 * {@code HH:mm:ss}.
 */
public record EffectiveDayResponse(
        LocalDate date,
        EffectiveDaySource source,
        List<WorkIntervalDto> intervals,
        List<LocalTime> times,
        LocalTime windowStart,
        LocalTime windowEnd
) {

    /**
     * Pre-15.8 convenience constructor: a day with no discrete times ({@code times == null}) and no
     * recorded working window. Keeps the backward-compatible call shape for INTERVAL/override/no-schedule
     * days.
     */
    public EffectiveDayResponse(LocalDate date, EffectiveDaySource source, List<WorkIntervalDto> intervals) {
        this(date, source, intervals, null, null, null);
    }

    /**
     * Pre-15.12 convenience constructor: the {@code intervals}/{@code times} shape that predates the
     * display-only working-window bounds. Keeps every existing caller compiling unchanged with a
     * {@code null} window (i.e. verbatim pre-15.12 behaviour).
     */
    public EffectiveDayResponse(
            LocalDate date, EffectiveDaySource source, List<WorkIntervalDto> intervals, List<LocalTime> times) {
        this(date, source, intervals, times, null, null);
    }

    /**
     * Phase 15.11: reduces this projection to the single boolean the CLIENT-facing calendar day-gating
     * endpoint needs (working / not working), with no hours, intervals, or times leaving the service
     * layer. A date is a working day when a {@link EffectiveDaySource#TEMPLATE} or
     * {@link EffectiveDaySource#OVERRIDE_CUSTOM} actually carries bookable content — either an interval
     * or a discrete time. {@link EffectiveDaySource#OVERRIDE_DAY_OFF} and
     * {@link EffectiveDaySource#NO_SCHEDULE} are never working days.
     *
     * <p>{@code times} is {@code null} for every day except an {@code EXPLICIT_TIMES} weekday (see class
     * Javadoc), so this null-checks it explicitly rather than assuming a non-null list — a
     * {@code TEMPLATE} day whose weekday has no defined interval (window covers the date, but that
     * specific day-of-week is uncovered) legitimately reaches this method with empty intervals and null
     * times.
     *
     * <p>{@code @JsonIgnore}: this follows JavaBean {@code isXxx()} getter convention, which Jackson would
     * otherwise pick up and serialize as an extra {@code workingDay} field on this record's JSON — an
     * unreviewed contract change on every endpoint that returns {@link EffectiveDayResponse} (e.g.
     * {@code GET /{masterId}/effective-schedule}). This method is an internal Java helper only, consumed
     * directly by {@code MasterScheduleService.getClientWorkingDays}.
     */
    @JsonIgnore
    public boolean isWorkingDay() {
        boolean hasContent = !intervals.isEmpty() || (times != null && !times.isEmpty());
        return (source == EffectiveDaySource.TEMPLATE || source == EffectiveDaySource.OVERRIDE_CUSTOM)
                && hasContent;
    }
}
