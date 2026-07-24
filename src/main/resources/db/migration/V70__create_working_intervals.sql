-- Phase 15.1 — Master Schedule Schema.
-- Canonical ordered working intervals for a weekly_schedule: one row per interval
-- (a day's working window minus its breaks decomposes into >=1 interval). ISO weekday 1=Mon..7=Sun.
-- Wall-clock TIME (minutes-from-midnight semantics); single-calendar-day windows only — end_time > start_time
-- forbids zero-length AND midnight-crossing intervals (see ARCHITECTURE "Interval/break validation").
-- Non-overlap of intervals on the same (schedule_id, day_of_week) cannot be expressed without GiST and is
-- enforced in the service layer (Phase 15.4).
CREATE TABLE working_intervals (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    schedule_id  UUID NOT NULL REFERENCES weekly_schedules(id) ON DELETE CASCADE,
    day_of_week  INTEGER NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),   -- ISO 1=Mon..7=Sun (mirrors legacy working_hours.day_of_week)
    start_time   TIME NOT NULL,
    end_time     TIME NOT NULL,
    CONSTRAINT chk_interval_order CHECK (end_time > start_time)          -- no zero-length, no midnight-cross
);

CREATE INDEX idx_working_intervals_schedule_day ON working_intervals (schedule_id, day_of_week);

CREATE UNIQUE INDEX uq_working_intervals_no_dup
    ON working_intervals (schedule_id, day_of_week, start_time, end_time);
