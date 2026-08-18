package com.beautica.master.service;

import com.beautica.master.dto.EffectiveDayResponse;
import com.beautica.master.dto.EffectiveDaySource;
import com.beautica.master.dto.ScheduleOverrideResponse;
import com.beautica.master.dto.WeeklyScheduleDayResponse;
import com.beautica.master.dto.WeeklyScheduleResponse;
import com.beautica.master.dto.WorkIntervalDto;
import com.beautica.master.entity.DiscreteTime;
import com.beautica.master.entity.OverrideDiscreteTime;
import com.beautica.master.entity.ScheduleException;
import com.beautica.master.entity.ScheduleExceptionInterval;
import com.beautica.master.entity.ScheduleExceptionKind;
import com.beautica.master.entity.WeekdayMode;
import com.beautica.master.entity.WeeklySchedule;
import com.beautica.master.entity.WeeklyScheduleDayWindow;
import com.beautica.master.entity.WorkingInterval;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Phase 15.4 / 15.8: entity → response-DTO mapping for the master schedule feature.
 *
 * <p>The 15.2 response records were deliberately left entity-decoupled (no {@code from(...)} factories),
 * so this {@code @Component} owns the projection. Keeping it out of {@link MasterScheduleService} honours
 * the controller/service/mapper split and the project's "mappers live in the feature package" rule.
 *
 * <p>All mapping is pure (no DB access); callers must pass entities whose lazy {@code intervals} and
 * {@code discreteTimes} collections were graph-fetched (the 15.4/15.8 repository finders do this).
 */
@Component
public class ScheduleMapper {

    private static final Comparator<WorkIntervalDto> BY_START =
            Comparator.comparing(WorkIntervalDto::startTime);

    /**
     * Maps a persisted weekly template to its read projection. Days are emitted ordered 1..7 (ISO), one
     * entry per weekday that has at least one interval OR at least one discrete time; bare weekdays are
     * omitted (an absent day == day off, matching the request shape's "empty list == off").
     *
     * <p>Phase 15.8: mode is derived per day — a weekday with ≥1 discrete-time row is
     * {@link WeekdayMode#EXPLICIT_TIMES} (carrying sorted {@code times}, empty {@code intervals}); otherwise
     * {@link WeekdayMode#INTERVAL} (carrying sorted {@code intervals}, empty {@code times}).
     */
    public WeeklyScheduleResponse toWeeklyScheduleResponse(WeeklySchedule schedule) {
        Map<Integer, List<WorkIntervalDto>> intervalsByDay = new TreeMap<>();
        for (WorkingInterval wi : schedule.getIntervals()) {
            intervalsByDay.computeIfAbsent(wi.getDayOfWeek(), d -> new ArrayList<>())
                    .add(new WorkIntervalDto(wi.getStartTime(), wi.getEndTime()));
        }
        Map<Integer, List<LocalTime>> timesByDay = new TreeMap<>();
        for (DiscreteTime dt : schedule.getDiscreteTimes()) {
            timesByDay.computeIfAbsent(dt.getDayOfWeek(), d -> new ArrayList<>())
                    .add(dt.getSlotTime());
        }

        // Phase 15.12: display-only working-window bounds, at most one row per weekday.
        Map<Integer, WeeklyScheduleDayWindow> windowsByDay = new TreeMap<>();
        for (WeeklyScheduleDayWindow dw : schedule.getDayWindows()) {
            windowsByDay.putIfAbsent(dw.getDayOfWeek(), dw);
        }

        Map<Integer, WeeklyScheduleDayResponse> byDay = new TreeMap<>();
        for (var e : timesByDay.entrySet()) {
            // An EXPLICIT_TIMES day has no window-with-breaks affordance, so it never carries window bounds.
            byDay.put(e.getKey(), new WeeklyScheduleDayResponse(
                    e.getKey(),
                    WeekdayMode.EXPLICIT_TIMES,
                    List.of(),
                    e.getValue().stream().sorted().distinct().toList()));
        }
        for (var e : intervalsByDay.entrySet()) {
            // A day is INTERVAL only when it has no discrete-time rows (mode exclusivity is a service
            // invariant; this guards against a malformed historical row mixing both).
            WeeklyScheduleDayWindow window = windowsByDay.get(e.getKey());
            byDay.putIfAbsent(e.getKey(), new WeeklyScheduleDayResponse(
                    e.getKey(),
                    WeekdayMode.INTERVAL,
                    e.getValue().stream().sorted(BY_START).toList(),
                    List.of(),
                    window != null ? window.getWindowStart() : null,
                    window != null ? window.getWindowEnd() : null));
        }

        List<WeeklyScheduleDayResponse> days = new ArrayList<>(byDay.values());
        return new WeeklyScheduleResponse(schedule.getId(), schedule.getValidFrom(), schedule.getValidTo(), days);
    }

    /**
     * Maps a persisted per-date override to its read projection. Phase 15.9: a {@code CUSTOM_HOURS}
     * override with ≥1 discrete-time row is {@link WeekdayMode#EXPLICIT_TIMES} (carrying sorted
     * {@code times}, empty {@code intervals}); otherwise {@link WeekdayMode#INTERVAL} (carrying sorted
     * {@code intervals}, empty {@code times}). A {@code DAY_OFF} reports {@link WeekdayMode#INTERVAL} with
     * both lists empty.
     */
    public ScheduleOverrideResponse toOverrideResponse(ScheduleException exception) {
        List<LocalTime> times = toOverrideDiscreteTimes(exception);
        if (!times.isEmpty()) {
            // An EXPLICIT_TIMES override has no window-with-breaks affordance — no window bounds.
            return new ScheduleOverrideResponse(
                    exception.getDate(), exception.getKind(),
                    WeekdayMode.EXPLICIT_TIMES, List.of(), times);
        }
        // Phase 15.12: display-only window bounds, non-null only on an INTERVAL CUSTOM_HOURS row that
        // recorded them (the service persists null for DAY_OFF / EXPLICIT_TIMES / legacy rows).
        return new ScheduleOverrideResponse(
                exception.getDate(), exception.getKind(),
                WeekdayMode.INTERVAL, toIntervalDtos(exception.getIntervals()), List.of(),
                exception.getWindowStart(), exception.getWindowEnd());
    }

    /**
     * Phase 15.9: the sorted, de-duplicated discrete start times of a per-date override. Empty when the
     * override has no discrete-time rows (i.e. it is an INTERVAL custom-hours override or a DAY_OFF).
     */
    public List<LocalTime> toOverrideDiscreteTimes(ScheduleException exception) {
        return exception.getDiscreteTimes().stream()
                .map(OverrideDiscreteTime::getSlotTime)
                .sorted()
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * Builds the canonical effective-day projection for an INTERVAL/override/no-schedule day (no discrete
     * times). Phase 15.8: {@code times} is {@code null} for these — see
     * {@link #toEffectiveDay(LocalDate, EffectiveDaySource, List, List)} for EXPLICIT_TIMES days.
     */
    public EffectiveDayResponse toEffectiveDay(
            LocalDate date,
            EffectiveDaySource source,
            List<WorkIntervalDto> intervals) {
        return new EffectiveDayResponse(date, source, intervals, null);
    }

    /**
     * Phase 15.8: effective-day projection carrying discrete {@code times} (an EXPLICIT_TIMES template day).
     * {@code intervals} still carries the derived window {@code [min..max]} so window-only consumers work.
     */
    public EffectiveDayResponse toEffectiveDay(
            LocalDate date,
            EffectiveDaySource source,
            List<WorkIntervalDto> intervals,
            List<LocalTime> times) {
        return new EffectiveDayResponse(date, source, intervals, times);
    }

    /**
     * Phase 15.12: effective-day projection carrying the resolved source's display-only working window, so
     * the mobile day editor (which hydrates from this projection) can derive breaks as
     * {@code window MINUS intervals} and recover a break flush against an edge of the working day.
     *
     * <p>Both bounds are {@code null} whenever the source stored none (every legacy row) — never
     * synthesized. {@code times} is {@code null} here: an EXPLICIT_TIMES day has no window-with-breaks
     * affordance and therefore never carries window bounds (use
     * {@link #toEffectiveDay(LocalDate, EffectiveDaySource, List, List)} for those).
     *
     * <p>Metadata only — {@code intervals} stays the single canonical source of availability and no slot
     * computation reads these bounds.
     */
    public EffectiveDayResponse toEffectiveDayWithWindow(
            LocalDate date,
            EffectiveDaySource source,
            List<WorkIntervalDto> intervals,
            LocalTime windowStart,
            LocalTime windowEnd) {
        return new EffectiveDayResponse(date, source, intervals, null, windowStart, windowEnd);
    }

    /**
     * Phase 15.12: the stored display-only working window of one ISO weekday of a weekly template, if the
     * client ever recorded one. Empty for every legacy day, every day off, and every EXPLICIT_TIMES day
     * (the service never writes a row for those) — the caller then projects {@code null} bounds and the
     * client falls back to gap reconstruction.
     *
     * <p>Traverses the batch-fetched {@code dayWindows} collection; callers must pass an entity loaded
     * inside an open transaction, exactly as {@link #toDiscreteTimesForDay} already requires.
     */
    public Optional<WeeklyScheduleDayWindow> findDayWindow(WeeklySchedule schedule, int isoDow) {
        return schedule.getDayWindows().stream()
                .filter(dw -> dw.getDayOfWeek() == isoDow)
                .findFirst();
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

    /**
     * Phase 15.8: the sorted, de-duplicated discrete start times of a weekly template for one ISO weekday.
     * Empty when the day has no discrete-time rows (i.e. it is an INTERVAL day).
     */
    public List<LocalTime> toDiscreteTimesForDay(WeeklySchedule schedule, int isoDow) {
        return schedule.getDiscreteTimes().stream()
                .filter(dt -> dt.getDayOfWeek() == isoDow)
                .map(DiscreteTime::getSlotTime)
                .sorted()
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * Phase 15.8: the derived display window {@code [min..max]} for an EXPLICIT_TIMES day as a single
     * interval, so existing window consumers keep working. The window is degenerate ({@code start == max},
     * i.e. a zero-length point) when a day has exactly one discrete time; callers that paint chips use the
     * {@code times} list, not this window, for that case.
     */
    public List<WorkIntervalDto> toDerivedWindow(List<LocalTime> sortedTimes) {
        if (sortedTimes.isEmpty()) {
            return List.of();
        }
        LocalTime min = sortedTimes.get(0);
        LocalTime max = sortedTimes.get(sortedTimes.size() - 1);
        return List.of(new WorkIntervalDto(min, max));
    }

    /** True when the override closes the date (no availability). */
    public boolean isDayOff(ScheduleException exception) {
        return exception.getKind() == ScheduleExceptionKind.DAY_OFF;
    }
}
