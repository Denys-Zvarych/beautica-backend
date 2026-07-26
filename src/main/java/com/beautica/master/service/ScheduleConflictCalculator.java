package com.beautica.master.service;

import com.beautica.common.TimeZones;
import com.beautica.master.dto.WorkIntervalDto;
import com.beautica.master.entity.ScheduleExceptionKind;
import com.beautica.master.entity.WeekdayMode;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Pure predicate for the "schedule override over existing bookings" feature (2026-07-26 design):
 * decides whether a single {@code CONFIRMED} booking survives an intended per-date override, given
 * only the override's shape ({@code kind}/{@code mode}/{@code intervals}/{@code times}) and the
 * booking's absolute {@code [startsAt, endsAt)} window.
 *
 * <p><b>No Spring, no repository, no clock.</b> The "belongs to the master", "status is CONFIRMED"
 * and "startsAt >= now" filters are the caller's responsibility (applied at the query level by
 * {@code ScheduleOverrideConflictService} — cheaper there, and this class stays a trivial,
 * deterministic function of its arguments, independently unit-testable with zero Spring context.
 *
 * <p><b>Conflict rule</b> (locked decisions D1/the design's "Conflict rule" section) — a booking
 * conflicts (i.e. is NOT fully covered by the intended availability for {@code date}) when:
 * <ul>
 *   <li>{@link ScheduleExceptionKind#DAY_OFF} — every booking on the date conflicts;</li>
 *   <li>{@link ScheduleExceptionKind#CUSTOM_HOURS} + {@link WeekdayMode#INTERVAL} — conflicts unless
 *       SOME new interval fully contains {@code [startsAt, endsAt)};</li>
 *   <li>{@link ScheduleExceptionKind#CUSTOM_HOURS} + {@link WeekdayMode#EXPLICIT_TIMES} — conflicts
 *       unless some discrete time equals the booking's local start time.</li>
 * </ul>
 * Widening hours (a superset interval, or the same discrete times plus more) therefore always
 * yields zero conflicts for bookings that already fit — the existing "extend my day" flow is
 * untouched.
 *
 * <p><b>Kyiv civil time.</b> {@code intervals}/{@code times} are {@link LocalTime} values that apply
 * to ONE Kyiv calendar date ({@code date} — the same date the caller grouped the booking under, by
 * its Kyiv-local {@code startsAt}). Each is converted to an absolute instant via
 * {@link TimeZones#KYIV} (DST-safe, unlike a fixed offset) before being compared against the
 * booking's real {@code startsAt}/{@code endsAt}.
 */
public final class ScheduleConflictCalculator {

    private ScheduleConflictCalculator() {
    }

    /**
     * @param kind           the intended override's kind
     * @param mode           the intended override's mode; only consulted when {@code kind} is
     *                       {@link ScheduleExceptionKind#CUSTOM_HOURS}
     * @param intervals      the intended override's intervals (INTERVAL mode); ignored otherwise
     * @param times          the intended override's discrete times (EXPLICIT_TIMES mode); ignored
     *                       otherwise
     * @param date           the Kyiv calendar date {@code intervals}/{@code times} apply to — must be
     *                       the booking's own Kyiv-local {@code startsAt} date
     * @param bookingStartsAt the booking's absolute start instant
     * @param bookingEndsAt   the booking's absolute end instant (exclusive)
     * @return {@code true} iff the booking is NOT fully covered by the intended availability
     */
    public static boolean conflicts(
            ScheduleExceptionKind kind,
            WeekdayMode mode,
            List<WorkIntervalDto> intervals,
            List<LocalTime> times,
            LocalDate date,
            OffsetDateTime bookingStartsAt,
            OffsetDateTime bookingEndsAt) {
        return switch (kind) {
            case DAY_OFF -> true;
            case CUSTOM_HOURS -> switch (mode) {
                case INTERVAL -> noIntervalFullyCovers(intervals, date, bookingStartsAt, bookingEndsAt);
                case EXPLICIT_TIMES -> noTimeMatchesStart(times, date, bookingStartsAt);
            };
        };
    }

    private static boolean noIntervalFullyCovers(
            List<WorkIntervalDto> intervals, LocalDate date, OffsetDateTime start, OffsetDateTime end) {
        if (intervals == null || intervals.isEmpty()) {
            return true;
        }
        return intervals.stream().noneMatch(interval -> fullyCovers(interval, date, start, end));
    }

    private static boolean fullyCovers(
            WorkIntervalDto interval, LocalDate date, OffsetDateTime start, OffsetDateTime end) {
        OffsetDateTime intervalStart = toKyivInstant(date, interval.startTime());
        OffsetDateTime intervalEnd = toKyivInstant(date, interval.endTime());
        return !start.isBefore(intervalStart) && !end.isAfter(intervalEnd);
    }

    private static boolean noTimeMatchesStart(List<LocalTime> times, LocalDate date, OffsetDateTime start) {
        if (times == null || times.isEmpty()) {
            return true;
        }
        return times.stream().noneMatch(time -> toKyivInstant(date, time).isEqual(start));
    }

    private static OffsetDateTime toKyivInstant(LocalDate date, LocalTime time) {
        return date.atTime(time).atZone(TimeZones.KYIV).toOffsetDateTime();
    }
}
