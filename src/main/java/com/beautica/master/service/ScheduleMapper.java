package com.beautica.master.service;

import com.beautica.master.dto.EffectiveDayResponse;
import com.beautica.master.dto.EffectiveDaySource;
import com.beautica.master.dto.ScheduleOverrideResponse;
import com.beautica.master.dto.WeeklyScheduleDayResponse;
import com.beautica.master.dto.WeeklyScheduleResponse;
import com.beautica.master.dto.WorkIntervalDto;
import com.beautica.master.entity.ScheduleException;
import com.beautica.master.entity.ScheduleExceptionInterval;
import com.beautica.master.entity.ScheduleExceptionKind;
import com.beautica.master.entity.WeeklySchedule;
import com.beautica.master.entity.WorkingInterval;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Phase 15.4: entity → response-DTO mapping for the master schedule feature.
 *
 * <p>The 15.2 response records were deliberately left entity-decoupled (no {@code from(...)} factories),
 * so this {@code @Component} owns the projection. Keeping it out of {@link MasterScheduleService} honours
 * the controller/service/mapper split and the project's "mappers live in the feature package" rule.
 *
 * <p>All mapping is pure (no DB access); callers must pass entities whose lazy {@code intervals}
 * collections were graph-fetched (the 15.4 repository finders do this).
 */
@Component
public class ScheduleMapper {

    private static final Comparator<WorkIntervalDto> BY_START =
            Comparator.comparing(WorkIntervalDto::startTime);

    /**
     * Maps a persisted weekly template to its read projection. Days are emitted ordered 1..7 (ISO),
     * one entry per weekday that has at least one interval; weekdays with no interval are omitted
     * (an absent day == day off, matching the request shape's "empty list == off").
     */
    public WeeklyScheduleResponse toWeeklyScheduleResponse(WeeklySchedule schedule) {
        Map<Integer, List<WorkIntervalDto>> byDay = new TreeMap<>();
        for (WorkingInterval wi : schedule.getIntervals()) {
            byDay.computeIfAbsent(wi.getDayOfWeek(), d -> new java.util.ArrayList<>())
                    .add(new WorkIntervalDto(wi.getStartTime(), wi.getEndTime()));
        }
        List<WeeklyScheduleDayResponse> days = byDay.entrySet().stream()
                .map(e -> new WeeklyScheduleDayResponse(
                        e.getKey(),
                        e.getValue().stream().sorted(BY_START).toList()))
                .toList();
        return new WeeklyScheduleResponse(schedule.getId(), schedule.getValidFrom(), schedule.getValidTo(), days);
    }

    /** Maps a persisted per-date override to its read projection. */
    public ScheduleOverrideResponse toOverrideResponse(ScheduleException exception) {
        return new ScheduleOverrideResponse(
                exception.getDate(),
                exception.getKind(),
                exception.getReason(),
                exception.getNote(),
                toIntervalDtos(exception.getIntervals()));
    }

    /** Builds the canonical effective-day projection from a resolved {@code source} + intervals. */
    public EffectiveDayResponse toEffectiveDay(
            LocalDate date,
            EffectiveDaySource source,
            List<WorkIntervalDto> intervals,
            com.beautica.master.entity.ScheduleExceptionReason reason) {
        return new EffectiveDayResponse(date, source, intervals, reason);
    }

    /** Maps an override's intervals (already graph-fetched), ordered by start time. */
    public List<WorkIntervalDto> toIntervalDtos(List<ScheduleExceptionInterval> intervals) {
        return intervals.stream()
                .map(i -> new WorkIntervalDto(i.getStartTime(), i.getEndTime()))
                .sorted(BY_START)
                .collect(Collectors.toList());
    }

    /** Maps a weekly template's intervals for one ISO weekday, ordered by start time. */
    public List<WorkIntervalDto> toIntervalDtosForDay(WeeklySchedule schedule, int isoDow) {
        return schedule.getIntervals().stream()
                .filter(wi -> wi.getDayOfWeek() == isoDow)
                .map(wi -> new WorkIntervalDto(wi.getStartTime(), wi.getEndTime()))
                .sorted(BY_START)
                .collect(Collectors.toList());
    }

    /** True when the override closes the date (no availability). */
    public boolean isDayOff(ScheduleException exception) {
        return exception.getKind() == ScheduleExceptionKind.DAY_OFF;
    }
}
