package com.beautica.master.repository;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * One row of {@link WeeklyScheduleRepository#findTemplateIntervalRowsByMasterIds}: a weekly template's
 * header ({@code scheduleId}, {@code masterId}, validity window) joined to ONE of its working intervals.
 * A template with no interval row (an all-EXPLICIT_TIMES or empty template) still yields one row, with
 * the three interval columns {@code null} (LEFT JOIN).
 *
 * <p>A narrow projection, not a managed {@code WeeklySchedule} graph (B523 perf fix): the strict search
 * verdict folds the templates of up to {@code StrictBookableResolver.CANDIDATE_CAP} masters, and
 * hydrating template + interval entities for that set dominated the resolve.
 */
public interface ScheduleTemplateIntervalRow {

    UUID getScheduleId();

    UUID getMasterId();

    LocalDate getValidFrom();

    /** {@code null} = open-ended template. */
    LocalDate getValidTo();

    /** ISO weekday 1..7; {@code null} when the template has no interval row. */
    Integer getDayOfWeek();

    LocalTime getStartTime();

    LocalTime getEndTime();
}
