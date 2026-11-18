package com.beautica.master.service;

import com.beautica.master.dto.WorkIntervalDto;
import com.beautica.master.entity.OverrideDiscreteTime;
import com.beautica.master.entity.ScheduleException;
import com.beautica.master.entity.ScheduleExceptionInterval;
import com.beautica.master.entity.ScheduleExceptionKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B523 perf fix: {@link ScheduleSpanBound} decides when the strict free-slot walk may be skipped.
 * An UNDER-estimate hides a bookable master from search and the public roster (SRCH-30, SAL-09), so
 * every source of a long span — template intervals on any weekday of any template, template discrete
 * times, custom-hours overrides, an override whose shape is not read — must reach the bound, plus the
 * one-hour DST slack.
 */
@DisplayName("ScheduleSpanBound — safe upper bound on the longest bookable span of a master's range")
class ScheduleSpanBoundTest {

    private static final Duration SLACK = ScheduleSpanBound.DST_SLACK;
    private static final int MONDAY = 1;
    private static final int THURSDAY = 4;
    private static final int SUNDAY = 7;

    @Test
    @DisplayName("no template and no override → only the DST slack")
    void should_returnOnlyTheSlack_when_scheduleIsEmpty() {
        Duration bound = ScheduleSpanBound.longestPossibleSpan(List.of(), List.of());

        assertThat(bound).isEqualTo(Duration.ofHours(1));
    }

    @Test
    @DisplayName("the longest interval of ANY weekday of ANY template wins (Sunday of the second template here)")
    void should_takeTheLongestIntervalAcrossWeekdaysAndTemplates_when_severalTemplatesOverlap() {
        ScheduleTemplate first = intervalTemplate(Map.of(
                MONDAY, List.of(interval(9, 11), interval(12, 14))));
        ScheduleTemplate second = intervalTemplate(Map.of(
                THURSDAY, List.of(interval(10, 13)),
                SUNDAY, List.of(interval(8, 18))));

        Duration bound = ScheduleSpanBound.longestPossibleSpan(List.of(first, second), List.of());

        assertThat(bound).as("Sunday 08:00–18:00 of the second template + slack")
                .isEqualTo(Duration.ofHours(10).plus(SLACK));
    }

    @Test
    @DisplayName("discrete start times bound the span from the EARLIEST time to midnight, never from the last one")
    void should_measureFromTheEarliestDiscreteTimeToMidnight_when_templateUsesExplicitTimes() {
        ScheduleTemplate times = new FixedTemplate(Map.of(),
                Map.of(THURSDAY, List.of(LocalTime.of(20, 0), LocalTime.of(22, 30))));

        Duration bound = ScheduleSpanBound.longestPossibleSpan(List.of(times), List.of());

        assertThat(bound).as("24:00 − 20:00 + slack").isEqualTo(Duration.ofHours(4).plus(SLACK));
    }

    @Test
    @DisplayName("a custom-hours override counts as a whole day, even when its intervals are short")
    void should_countAWholeDay_when_customHoursOverrideHasIntervals() {
        ScheduleTemplate template = intervalTemplate(Map.of(MONDAY, List.of(interval(9, 11))));
        ScheduleException shortDay = customHours(interval(10, 12));

        Duration bound = ScheduleSpanBound.longestPossibleSpan(List.of(template), List.of(shortDay));

        assertThat(bound).as("24 h + slack — the override's intervals are never trusted as the bound")
                .isEqualTo(Duration.ofHours(24).plus(SLACK));
    }

    @Test
    @DisplayName("an override with BOTH interval rows and an earlier explicit time is never under-estimated")
    void should_notUnderEstimate_when_overrideHasBothIntervalsAndExplicitTimes() {
        ScheduleTemplate template = intervalTemplate(Map.of(MONDAY, List.of(interval(9, 11))));
        ScheduleException mixed = customHours(interval(20, 21));
        mixed.getDiscreteTimes().add(OverrideDiscreteTime.builder()
                .exception(mixed).slotTime(LocalTime.of(6, 0)).build());

        Duration bound = ScheduleSpanBound.longestPossibleSpan(List.of(template), List.of(mixed));

        assertThat(bound).as("the 06:00 start can run to midnight (18 h); the bound must cover it, not the 1 h interval")
                .isGreaterThanOrEqualTo(Duration.ofHours(18).plus(SLACK))
                .isEqualTo(Duration.ofHours(24).plus(SLACK));
    }

    @Test
    @DisplayName("a DAY_OFF override adds nothing; the template interval still sets the bound")
    void should_ignoreDayOff_when_overrideIsADayOff() {
        ScheduleTemplate template = intervalTemplate(Map.of(MONDAY, List.of(interval(9, 11))));
        ScheduleException dayOff = ScheduleException.builder()
                .date(LocalDate.of(2026, 11, 23)).kind(ScheduleExceptionKind.DAY_OFF).build();

        Duration bound = ScheduleSpanBound.longestPossibleSpan(List.of(template), List.of(dayOff));

        assertThat(bound).isEqualTo(Duration.ofHours(2).plus(SLACK));
    }

    @Test
    @DisplayName("a custom override whose intervals are not read (explicit times) counts as a whole day")
    void should_assumeAWholeDay_when_customOverrideHasNoIntervals() {
        ScheduleTemplate template = intervalTemplate(Map.of(MONDAY, List.of(interval(9, 11))));
        ScheduleException explicitTimes = ScheduleException.builder()
                .date(LocalDate.of(2026, 11, 23)).kind(ScheduleExceptionKind.CUSTOM_HOURS).build();

        Duration bound = ScheduleSpanBound.longestPossibleSpan(List.of(template), List.of(explicitTimes));

        assertThat(bound).as("24 h + slack — conservative, never skips a fit")
                .isEqualTo(Duration.ofHours(25));
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────

    private static WorkIntervalDto interval(int startHour, int endHour) {
        return new WorkIntervalDto(LocalTime.of(startHour, 0), LocalTime.of(endHour, 0));
    }

    private static ScheduleTemplate intervalTemplate(Map<Integer, List<WorkIntervalDto>> intervalsByDow) {
        return new FixedTemplate(intervalsByDow, Map.of());
    }

    private static ScheduleException customHours(WorkIntervalDto window) {
        ScheduleException override = ScheduleException.builder()
                .date(LocalDate.of(2026, 11, 24)).kind(ScheduleExceptionKind.CUSTOM_HOURS)
                .intervals(new ArrayList<>()).build();
        override.getIntervals().add(ScheduleExceptionInterval.builder()
                .exception(override).startTime(window.startTime()).endTime(window.endTime()).build());
        return override;
    }

    private record FixedTemplate(
            Map<Integer, List<WorkIntervalDto>> intervals,
            Map<Integer, List<LocalTime>> times) implements ScheduleTemplate {

        @Override
        public LocalDate validFrom() {
            return LocalDate.of(2026, 1, 1);
        }

        @Override
        public LocalDate validTo() {
            return null;
        }

        @Override
        public List<WorkIntervalDto> intervalsForDay(int isoDow) {
            return intervals.getOrDefault(isoDow, List.of());
        }

        @Override
        public List<LocalTime> discreteTimesForDay(int isoDow) {
            return times.getOrDefault(isoDow, List.of());
        }
    }
}
