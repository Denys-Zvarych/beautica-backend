package com.beautica.master.service;

import com.beautica.master.dto.WorkIntervalDto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * One weekly template as the date fold ({@code MasterScheduleService#foldDate}) reads it: its validity
 * window and, per ISO weekday, its sorted intervals and its sorted, de-duplicated discrete start times.
 *
 * <p>Two sources, one fold (B523 perf fix): {@link EntityScheduleTemplate} adapts a managed
 * {@code WeeklySchedule} (single-master and display paths, which also need the entity's day windows),
 * {@link ProjectedScheduleTemplates} builds templates from the narrow batched projection the free-slot
 * gate loads. Both answer through the same {@link ScheduleMapper} ordering rules, so the fold's verdict
 * cannot depend on which source fed it.
 */
interface ScheduleTemplate {

    LocalDate validFrom();

    /** {@code null} = open-ended. */
    LocalDate validTo();

    /** The weekday's intervals ordered by start time; empty for an EXPLICIT_TIMES or closed weekday. */
    List<WorkIntervalDto> intervalsForDay(int isoDow);

    /** The weekday's discrete start times, sorted and distinct; empty for an INTERVAL weekday. */
    List<LocalTime> discreteTimesForDay(int isoDow);
}
