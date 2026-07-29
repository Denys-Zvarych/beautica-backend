package com.beautica.master.service;

import com.beautica.common.TimeZones;
import com.beautica.master.dto.WorkIntervalDto;
import com.beautica.master.entity.ScheduleExceptionKind;
import com.beautica.master.entity.WeekdayMode;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the pure conflict predicate backing the 2026-07-26 "schedule override over
 * existing bookings" design. No Spring context — every case is a direct function call.
 */
class ScheduleConflictCalculatorTest {

    private static final LocalDate DATE = LocalDate.of(2026, 8, 10);

    private static OffsetDateTime kyiv(LocalTime time) {
        return DATE.atTime(time).atZone(TimeZones.KYIV).toOffsetDateTime();
    }

    @Test
    void should_conflict_when_kindIsDayOff() {
        OffsetDateTime start = kyiv(LocalTime.of(10, 0));
        OffsetDateTime end = kyiv(LocalTime.of(11, 0));

        boolean conflicts = ScheduleConflictCalculator.conflicts(
                ScheduleExceptionKind.DAY_OFF, WeekdayMode.INTERVAL, List.of(), List.of(),
                DATE, start, end);

        assertThat(conflicts).isTrue();
    }

    @Test
    void should_notConflict_when_intervalFullyContainsBooking() {
        List<WorkIntervalDto> intervals = List.of(new WorkIntervalDto(LocalTime.of(9, 0), LocalTime.of(17, 0)));
        OffsetDateTime start = kyiv(LocalTime.of(10, 0));
        OffsetDateTime end = kyiv(LocalTime.of(11, 0));

        boolean conflicts = ScheduleConflictCalculator.conflicts(
                ScheduleExceptionKind.CUSTOM_HOURS, WeekdayMode.INTERVAL, intervals, null,
                DATE, start, end);

        assertThat(conflicts).isFalse();
    }

    @Test
    void should_notConflict_when_bookingExactlyMatchesIntervalBounds() {
        List<WorkIntervalDto> intervals = List.of(new WorkIntervalDto(LocalTime.of(10, 0), LocalTime.of(11, 0)));
        OffsetDateTime start = kyiv(LocalTime.of(10, 0));
        OffsetDateTime end = kyiv(LocalTime.of(11, 0));

        boolean conflicts = ScheduleConflictCalculator.conflicts(
                ScheduleExceptionKind.CUSTOM_HOURS, WeekdayMode.INTERVAL, intervals, null,
                DATE, start, end);

        assertThat(conflicts).isFalse();
    }

    @Test
    void should_conflict_when_bookingExtendsPastEveryNewInterval() {
        // Narrowed hours: the new window ends at 10:30, but the booking runs until 11:00.
        List<WorkIntervalDto> intervals = List.of(new WorkIntervalDto(LocalTime.of(9, 0), LocalTime.of(10, 30)));
        OffsetDateTime start = kyiv(LocalTime.of(10, 0));
        OffsetDateTime end = kyiv(LocalTime.of(11, 0));

        boolean conflicts = ScheduleConflictCalculator.conflicts(
                ScheduleExceptionKind.CUSTOM_HOURS, WeekdayMode.INTERVAL, intervals, null,
                DATE, start, end);

        assertThat(conflicts).isTrue();
    }

    @Test
    void should_conflict_when_bookingStartsBeforeEveryNewInterval() {
        List<WorkIntervalDto> intervals = List.of(new WorkIntervalDto(LocalTime.of(10, 30), LocalTime.of(17, 0)));
        OffsetDateTime start = kyiv(LocalTime.of(10, 0));
        OffsetDateTime end = kyiv(LocalTime.of(11, 0));

        boolean conflicts = ScheduleConflictCalculator.conflicts(
                ScheduleExceptionKind.CUSTOM_HOURS, WeekdayMode.INTERVAL, intervals, null,
                DATE, start, end);

        assertThat(conflicts).isTrue();
    }

    @Test
    void should_notConflict_when_someIntervalCoversBookingAmongSeveral() {
        List<WorkIntervalDto> intervals = List.of(
                new WorkIntervalDto(LocalTime.of(8, 0), LocalTime.of(9, 30)),
                new WorkIntervalDto(LocalTime.of(10, 0), LocalTime.of(12, 0)));
        OffsetDateTime start = kyiv(LocalTime.of(10, 30));
        OffsetDateTime end = kyiv(LocalTime.of(11, 30));

        boolean conflicts = ScheduleConflictCalculator.conflicts(
                ScheduleExceptionKind.CUSTOM_HOURS, WeekdayMode.INTERVAL, intervals, null,
                DATE, start, end);

        assertThat(conflicts).isFalse();
    }

    @Test
    void should_conflict_when_intervalModeHasNoIntervals() {
        OffsetDateTime start = kyiv(LocalTime.of(10, 0));
        OffsetDateTime end = kyiv(LocalTime.of(11, 0));

        boolean conflicts = ScheduleConflictCalculator.conflicts(
                ScheduleExceptionKind.CUSTOM_HOURS, WeekdayMode.INTERVAL, List.of(), null,
                DATE, start, end);

        assertThat(conflicts).isTrue();
    }

    @Test
    void should_notConflict_when_explicitTimeMatchesBookingStart() {
        List<LocalTime> times = List.of(LocalTime.of(9, 0), LocalTime.of(10, 0), LocalTime.of(11, 0));
        OffsetDateTime start = kyiv(LocalTime.of(10, 0));
        OffsetDateTime end = kyiv(LocalTime.of(10, 45));

        boolean conflicts = ScheduleConflictCalculator.conflicts(
                ScheduleExceptionKind.CUSTOM_HOURS, WeekdayMode.EXPLICIT_TIMES, null, times,
                DATE, start, end);

        assertThat(conflicts).isFalse();
    }

    @Test
    void should_conflict_when_noExplicitTimeMatchesBookingStart() {
        List<LocalTime> times = List.of(LocalTime.of(9, 0), LocalTime.of(11, 0));
        OffsetDateTime start = kyiv(LocalTime.of(10, 0));
        OffsetDateTime end = kyiv(LocalTime.of(10, 45));

        boolean conflicts = ScheduleConflictCalculator.conflicts(
                ScheduleExceptionKind.CUSTOM_HOURS, WeekdayMode.EXPLICIT_TIMES, null, times,
                DATE, start, end);

        assertThat(conflicts).isTrue();
    }

    @Test
    void should_conflict_when_explicitTimesModeHasNoTimes() {
        OffsetDateTime start = kyiv(LocalTime.of(10, 0));
        OffsetDateTime end = kyiv(LocalTime.of(10, 45));

        boolean conflicts = ScheduleConflictCalculator.conflicts(
                ScheduleExceptionKind.CUSTOM_HOURS, WeekdayMode.EXPLICIT_TIMES, null, List.of(),
                DATE, start, end);

        assertThat(conflicts).isTrue();
    }

    @Test
    void should_notConflict_when_explicitTimeMatchesButBookingRunsLongerThanNextTime() {
        // EXPLICIT_TIMES coverage is about the START time only — a booking's own duration was
        // already validated at creation time against the schedule that was in force then; the
        // discrete-time rule here only asks "does the booking still start on a legal slot".
        List<LocalTime> times = List.of(LocalTime.of(10, 0), LocalTime.of(10, 15));
        OffsetDateTime start = kyiv(LocalTime.of(10, 0));
        OffsetDateTime end = kyiv(LocalTime.of(10, 40));

        boolean conflicts = ScheduleConflictCalculator.conflicts(
                ScheduleExceptionKind.CUSTOM_HOURS, WeekdayMode.EXPLICIT_TIMES, null, times,
                DATE, start, end);

        assertThat(conflicts).isFalse();
    }
}
