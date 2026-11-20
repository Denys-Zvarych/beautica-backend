package com.beautica.master.service;

import com.beautica.master.dto.WorkIntervalDto;
import com.beautica.master.entity.WeeklySchedule;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * {@link ScheduleTemplate} over a managed {@link WeeklySchedule} — delegates verbatim to the
 * {@link ScheduleMapper} helpers the fold always used, so the entity paths' results are unchanged.
 * Must be read inside the transaction that loaded the entity (lazy {@code discreteTimes}).
 */
record EntityScheduleTemplate(WeeklySchedule schedule, ScheduleMapper mapper) implements ScheduleTemplate {

    @Override
    public LocalDate validFrom() {
        return schedule.getValidFrom();
    }

    @Override
    public LocalDate validTo() {
        return schedule.getValidTo();
    }

    @Override
    public List<WorkIntervalDto> intervalsForDay(int isoDow) {
        return mapper.toIntervalDtosForDay(schedule, isoDow);
    }

    @Override
    public List<LocalTime> discreteTimesForDay(int isoDow) {
        return mapper.toDiscreteTimesForDay(schedule, isoDow);
    }
}
