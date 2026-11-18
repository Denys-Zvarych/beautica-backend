package com.beautica.master.repository;

import java.time.LocalTime;
import java.util.UUID;

/**
 * One discrete start time of a weekly template ({@link WeeklyScheduleRepository#findTemplateTimeRowsByScheduleIds})
 * — the projection twin of {@code WeeklySchedule#getDiscreteTimes()} for the batched fold (B523 perf fix).
 */
public interface ScheduleTemplateTimeRow {

    UUID getScheduleId();

    /** ISO weekday 1..7. */
    int getDayOfWeek();

    LocalTime getSlotTime();
}
