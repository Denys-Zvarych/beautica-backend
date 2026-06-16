-- Phase 15.8 — Discrete Working Times (EXPLICIT_TIMES mode).
-- A weekday of a weekly_schedule is one of two modes, derived (no mode column, zero backfill):
--   INTERVAL       — has >=1 working_intervals row (existing, default; legacy days resolve here).
--   EXPLICIT_TIMES — has >=1 working_interval_times row (new): a set of discrete bookable START times.
-- A day has EITHER interval rows OR discrete-time rows, never both (enforced in service + DTO @AssertTrue;
-- Postgres CHECK cannot reference the sibling working_intervals table).
--
-- Discrete times are POINTS, not spans, so they cannot live in working_intervals: chk_interval_order
-- requires end_time > start_time. Storing them in their own child table keeps the no-cross-midnight
-- contract true by construction and avoids fake zero-length spans. No per-slot duration (booking concern).
-- day_of_week is ISO 1=Mon..7=Sun, mirroring working_intervals.day_of_week (INTEGER for column-type parity).
CREATE TABLE working_interval_times (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    schedule_id  UUID NOT NULL REFERENCES weekly_schedules(id) ON DELETE CASCADE,
    day_of_week  INTEGER NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),   -- ISO 1=Mon..7=Sun
    slot_time    TIME NOT NULL,
    CONSTRAINT uq_working_interval_times_no_dup UNIQUE (schedule_id, day_of_week, slot_time)
);

CREATE INDEX idx_working_interval_times_schedule_day ON working_interval_times (schedule_id, day_of_week);

COMMENT ON TABLE working_interval_times IS
    'Phase 15.8: discrete bookable start times for an EXPLICIT_TIMES weekday of a weekly_schedule. '
    'Mode is derived: a (schedule_id, day_of_week) with >=1 row IS EXPLICIT_TIMES, else INTERVAL. '
    'Mode exclusivity (no interval rows + discrete rows on the same day) is a service-layer invariant.';
