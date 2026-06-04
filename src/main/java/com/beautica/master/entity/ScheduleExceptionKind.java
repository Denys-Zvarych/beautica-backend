package com.beautica.master.entity;

/**
 * Phase 15.1: discriminator for a {@link ScheduleException}.
 *
 * <ul>
 *   <li>{@link #DAY_OFF} — a closure for the date; carries a {@code reason}; zero interval rows.</li>
 *   <li>{@link #CUSTOM_HOURS} — different working intervals that date; no {@code reason}; one or more
 *       {@link ScheduleExceptionInterval} rows.</li>
 * </ul>
 */
public enum ScheduleExceptionKind {
    DAY_OFF,
    CUSTOM_HOURS
}
