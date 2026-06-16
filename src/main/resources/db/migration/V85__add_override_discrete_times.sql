-- Phase 15.9 — Discrete Working Times for per-date overrides (EXPLICIT_TIMES on schedule_exceptions).
-- Extends the EXPLICIT_TIMES mode (V84, weekly) to a per-date CUSTOM_HOURS override. A custom-hours
-- override for a date is one of two modes, derived (no mode column, zero backfill):
--   INTERVAL       — has >=1 schedule_exception_intervals row (existing, default; legacy overrides resolve here).
--   EXPLICIT_TIMES — has >=1 schedule_exception_times row (new): a set of discrete bookable START times.
-- An override has EITHER interval rows OR discrete-time rows, never both (enforced in service + DTO
-- @AssertTrue; Postgres CHECK cannot reference the sibling schedule_exception_intervals table).
--
-- Discrete times are POINTS, not spans, so they cannot live in schedule_exception_intervals: chk_exc_interval_order
-- requires end_time > start_time. Storing them in their own child table keeps the no-cross-midnight
-- contract true by construction and avoids fake zero-length spans. No per-slot duration (booking concern).
-- The override is already date-scoped, so (unlike V84's weekly table) there is no day_of_week column.
CREATE TABLE schedule_exception_times (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    exception_id  UUID NOT NULL REFERENCES schedule_exceptions(id) ON DELETE CASCADE,
    slot_time     TIME NOT NULL,
    CONSTRAINT uq_schedule_exception_times_no_dup UNIQUE (exception_id, slot_time)
);

CREATE INDEX idx_schedule_exception_times_exception ON schedule_exception_times (exception_id);

COMMENT ON TABLE schedule_exception_times IS
    'Phase 15.9: discrete bookable start times for an EXPLICIT_TIMES per-date CUSTOM_HOURS override. '
    'Mode is derived: an exception with >=1 row IS EXPLICIT_TIMES, else INTERVAL. '
    'Mode exclusivity (no interval rows + discrete rows on the same override) is a service-layer invariant.';
