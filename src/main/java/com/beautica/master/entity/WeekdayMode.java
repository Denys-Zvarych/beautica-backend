package com.beautica.master.entity;

/**
 * Phase 15.8: the mode of a single weekday within a {@link WeeklySchedule}.
 *
 * <p>Derived from storage, never persisted as a column: a {@code (schedule, dayOfWeek)} with at least one
 * {@link DiscreteTime} row is {@link #EXPLICIT_TIMES}; otherwise (interval rows, or nothing) it is
 * {@link #INTERVAL}. The mode is surfaced explicitly on the wire DTOs for contract clarity, but legacy
 * interval days resolve to {@link #INTERVAL} automatically with zero backfill.
 */
public enum WeekdayMode {

    /** Continuous working window(s) with optional client-side breaks — stored as {@code working_intervals}. */
    INTERVAL,

    /** A set of discrete bookable start times — stored as {@code working_interval_times}. */
    EXPLICIT_TIMES
}
