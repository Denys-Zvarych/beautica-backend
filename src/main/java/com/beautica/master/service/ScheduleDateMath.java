package com.beautica.master.service;

import com.beautica.common.DateRange;
import com.beautica.common.TimeZones;
import com.beautica.common.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

/**
 * Single home for every piece of calendar arithmetic the Master Schedule feature needs.
 *
 * <p>This component codifies the normative edge-case rules locked in
 * {@code ARCHITECTURE-backend.md § "Master Schedule & Availability"}:
 * <ul>
 *   <li><strong>Kyiv civil time</strong> — {@code today()} is derived from a {@code Clock} pinned to
 *       {@link TimeZones#KYIV}, never the server default zone. Around DST and around UTC midnight the
 *       Kyiv civil date can differ from the UTC date; this is the only correct source of "today".</li>
 *   <li><strong>Leap-year / leap-day safety</strong> — validity presets use {@link TemporalAdjusters}
 *       and the documented {@code plusMonths}/{@code plusYears} clamp instead of naive arithmetic that
 *       would land on a non-existent Feb 29.</li>
 *   <li><strong>Far-future cap</strong> — {@code today + 2 years} (mirrors the calendar bounds guard in
 *       {@code MasterController}).</li>
 *   <li><strong>Bounded expansion</strong> — per-date materialisation is capped at 366 days to prevent
 *       unbounded loops across multi-year ranges.</li>
 *   <li><strong>ISO weekday</strong> — 1=Mon..7=Sun, matching {@code working_intervals.day_of_week}.</li>
 * </ul>
 *
 * <p>Instance (not static) so the clock is mockable: 15.7 hardening tests pin DST / leap-year behaviour
 * with a fixed {@code Clock} and no Spring / DB.
 */
@Component
public class ScheduleDateMath {

    /**
     * Hard upper bound on how many years ahead any validity window or propagation end may reach.
     * Mirrors the existing {@code MasterController} calendar bounds guard.
     */
    private static final int FAR_FUTURE_CAP_YEARS = 2;

    /**
     * Hard lower bound on how far into the past a <em>read</em> window may reach: {@code today - 1 year}.
     * Mirrors the {@code MasterController} calendar bounds guard so a caller cannot request an
     * arbitrarily far-past {@code from} (e.g. year 1000) and trigger an oversized scan.
     */
    private static final int FAR_PAST_CAP_YEARS = 1;

    /**
     * Maximum {@link ChronoUnit#DAYS} <em>between</em> the endpoints of a per-date expansion. A value of
     * 365 caps the materialised list at 366 inclusive dates (a full leap year). Wider spans are rejected
     * so callers cannot trigger unbounded loops or queries.
     */
    private static final long MAX_EXPANSION_SPAN_DAYS = 365L;

    /** Clock pinned to the Kyiv zone once at construction — never re-zoned per call. */
    private final Clock kyivClock;

    public ScheduleDateMath(Clock clock) {
        this.kyivClock = clock.withZone(TimeZones.KYIV);
    }

    /**
     * Today's <em>civil</em> date in Kyiv. Uses the Kyiv-pinned clock so that an instant late in the
     * UTC day (e.g. 22:30Z in summer) correctly resolves to the next Kyiv calendar date.
     */
    public LocalDate today() {
        return LocalDate.now(kyivClock);
    }

    /** A date strictly before today (Kyiv). Today itself is not past. */
    public boolean isPast(LocalDate date) {
        return date.isBefore(today());
    }

    /** Today and any future date are editable; past dates are frozen. */
    public boolean isEditable(LocalDate date) {
        return !isPast(date);
    }

    /**
     * Far-future cap: {@code today + 2 years}. {@link LocalDate#plusYears(long)} clamps a Feb-29 origin
     * to Feb 28 in a non-leap target year (e.g. 2024-02-29 → 2026-02-28), which is the correct behaviour.
     */
    public LocalDate cap() {
        return today().plusYears(FAR_FUTURE_CAP_YEARS);
    }

    /**
     * Far-past floor for read windows: {@code today - 1 year}. {@link LocalDate#minusYears(long)} clamps a
     * Feb-29 origin to Feb 28 in a non-leap target year, which is the correct behaviour.
     */
    public LocalDate pastFloor() {
        return today().minusYears(FAR_PAST_CAP_YEARS);
    }

    /**
     * Guards a bounded availability/validity window: {@code from} may not be in the past, {@code to} may
     * not exceed the far-future {@link #cap()}, and {@code from} may not be after {@code to}. Violations
     * surface as a 400 {@link BusinessException}.
     */
    public void assertWithinBounds(LocalDate from, LocalDate to) {
        LocalDate today = today();
        if (from.isBefore(today)) {
            throw new BusinessException("Schedule range start must not be in the past");
        }
        if (to.isAfter(today.plusYears(FAR_FUTURE_CAP_YEARS))) {
            throw new BusinessException("Schedule range end exceeds the allowed two-year window");
        }
        if (from.isAfter(to)) {
            throw new BusinessException("Schedule range start must not be after its end");
        }
    }

    /**
     * Guards a bounded <em>read</em> window (Phase 15.5 GET endpoints). Unlike
     * {@link #assertWithinBounds}, the start <b>may be in the past</b> (the calendar shows greyed
     * history), but only as far back as the {@link #pastFloor() one-year past floor}; the window must
     * also be ordered, span no more than 366 inclusive days, and not reach past the far-future
     * {@link #cap()}. An arbitrarily far-past {@code from} (e.g. year 1000) is rejected so callers
     * cannot trigger an oversized scan. Violations surface as a 400 {@link BusinessException}.
     */
    public void assertExpandable(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new BusinessException("Both 'from' and 'to' are required");
        }
        if (from.isAfter(to)) {
            throw new BusinessException("Schedule range start must not be after its end");
        }
        if (from.isBefore(pastFloor())) {
            throw new BusinessException("Schedule range start exceeds the allowed one-year past window");
        }
        if (to.isAfter(cap())) {
            throw new BusinessException("Schedule range end exceeds the allowed two-year window");
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_EXPANSION_SPAN_DAYS) {
            throw new BusinessException("Date range exceeds the maximum of 366 days");
        }
    }

    /**
     * "Весь поточний місяць" preset: from the later of today and the 1st of the month, through the last
     * day of the current month. {@code lastDayOfMonth()} is leap-aware (Feb → 28 or 29 correctly).
     */
    public DateRange wholeCurrentMonth() {
        LocalDate today = today();
        LocalDate firstOfMonth = today.withDayOfMonth(1);
        LocalDate from = today.isAfter(firstOfMonth) ? today : firstOfMonth;
        LocalDate to = today.with(TemporalAdjusters.lastDayOfMonth());
        return new DateRange(from, to);
    }

    /**
     * "Наступні N місяців" preset: from today through {@code today.plusMonths(n)}.
     * {@link LocalDate#plusMonths(long)} already clamps an end-of-month origin onto a shorter target
     * month (2024-01-31 + 1mo → 2024-02-29; 2023-01-31 + 1mo → 2023-02-28), which is the documented,
     * correct clamp — do not hand-roll it.
     */
    public DateRange nextNMonths(int n) {
        if (n <= 0) {
            throw new BusinessException("Month count must be positive");
        }
        LocalDate today = today();
        return new DateRange(today, today.plusMonths(n));
    }

    /**
     * "Весь рік" preset: from today through Dec 31 of the current year via
     * {@link TemporalAdjusters#lastDayOfYear()} — <strong>not</strong> {@code plusYears(1)}, which would
     * roll into next year and could wrap a Feb 29 origin.
     */
    public DateRange wholeYear() {
        LocalDate today = today();
        return new DateRange(today, today.with(TemporalAdjusters.lastDayOfYear()));
    }

    /**
     * Materialises every date in {@code [from, to]} inclusive. Rejects spans wider than 366 inclusive
     * days (a full leap year) to prevent unbounded materialisation. A span crossing Feb 29 in a leap
     * year naturally includes Feb 29.
     */
    public List<LocalDate> expandInclusive(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new BusinessException("Expansion range start must not be after its end");
        }
        long spanDays = ChronoUnit.DAYS.between(from, to);
        if (spanDays > MAX_EXPANSION_SPAN_DAYS) {
            throw new BusinessException("Date expansion exceeds the maximum of 366 days");
        }
        int size = (int) spanDays + 1; // inclusive of both endpoints; spanDays bounded ≤ 365
        List<LocalDate> dates = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            dates.add(from.plusDays(i));
        }
        return dates;
    }

    /** ISO day-of-week: 1=Monday .. 7=Sunday, matching {@code working_intervals.day_of_week}. */
    public int isoDow(LocalDate date) {
        return date.getDayOfWeek().getValue();
    }
}
