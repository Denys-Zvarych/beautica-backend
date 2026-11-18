package com.beautica.master.service;

import com.beautica.master.dto.WorkIntervalDto;
import com.beautica.master.entity.ScheduleException;
import com.beautica.master.entity.ScheduleExceptionKind;

import java.time.Duration;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;

/**
 * Computes {@link SpanBoundedDays#longestPossibleSpan()} for one master from the rows the batched fold
 * already holds — see that interface for the safety argument. Reads only template intervals, template
 * discrete times (the batch's one lazy statement, which the fold issues anyway) and override kinds; never
 * an override's intervals or discrete times — every {@code CUSTOM_HOURS} override counts as a full day.
 */
final class ScheduleSpanBound {

    /** A Kyiv civil day is at most 25 h (DST fall-back): the slack keeps the bound from ever skipping a fit. */
    static final Duration DST_SLACK = Duration.ofHours(1);

    private static final Duration FULL_DAY = Duration.ofDays(1);
    private static final int FIRST_ISO_DOW = 1;
    private static final int LAST_ISO_DOW = 7;

    private ScheduleSpanBound() {
    }

    static Duration longestPossibleSpan(
            Collection<ScheduleTemplate> templates, Collection<ScheduleException> overrides) {
        Duration longest = Duration.ZERO;
        for (ScheduleTemplate template : templates) {
            for (int dow = FIRST_ISO_DOW; dow <= LAST_ISO_DOW; dow++) {
                longest = max(longest, longestOfIntervals(template.intervalsForDay(dow)));
                longest = max(longest, spanFromEarliest(template.discreteTimesForDay(dow)));
            }
        }
        for (ScheduleException override : overrides) {
            longest = max(longest, overrideSpan(override));
        }
        return longest.plus(DST_SLACK);
    }

    /**
     * Every {@code CUSTOM_HOURS} override is a whole day (B523 audit cycle 2). Mode exclusivity (intervals
     * XOR discrete times) is only a service-layer invariant; an override carrying both would let the
     * slot walk start at an explicit time outside its intervals, so measuring the intervals alone could
     * UNDER-estimate and skip a bookable master. A full day never under-estimates and reads no lazy
     * collection — the cost is only that a master with an override in range is walked, not skipped.
     */
    private static Duration overrideSpan(ScheduleException override) {
        return override.getKind() == ScheduleExceptionKind.DAY_OFF ? Duration.ZERO : FULL_DAY;
    }

    private static Duration longestOfIntervals(List<WorkIntervalDto> intervals) {
        Duration longest = Duration.ZERO;
        for (WorkIntervalDto interval : intervals) {
            longest = max(longest, span(interval.startTime(), interval.endTime()));
        }
        return longest;
    }

    /** {@code 24:00} minus the earliest declared time: no declared start can run past the day's end. */
    private static Duration spanFromEarliest(List<LocalTime> sortedTimes) {
        return sortedTimes.isEmpty() ? Duration.ZERO : untilMidnight(sortedTimes.get(0));
    }

    /**
     * An end at or before the start cannot be persisted ({@code chk_interval_order}); were one ever read,
     * the slot walk would wrap it to the next day, so it counts as a whole day — never shorter.
     */
    private static Duration span(LocalTime start, LocalTime end) {
        return end.isAfter(start) ? Duration.between(start, end) : FULL_DAY;
    }

    private static Duration untilMidnight(LocalTime start) {
        return FULL_DAY.minus(Duration.between(LocalTime.MIDNIGHT, start));
    }

    private static Duration max(Duration a, Duration b) {
        return a.compareTo(b) >= 0 ? a : b;
    }
}
