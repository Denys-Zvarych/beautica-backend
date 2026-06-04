-- Phase 15.1 — Master Schedule Schema.
-- Extend schedule_exceptions to support custom-hours overrides in addition to closures.
-- kind discriminates: DAY_OFF (closure, carries a reason, zero interval rows — today's behaviour)
-- vs CUSTOM_HOURS (different intervals that date, no reason, >=1 interval rows).
-- DEFAULT 'DAY_OFF' keeps every existing row valid; existing (master_id, date) uniqueness is preserved.
ALTER TABLE schedule_exceptions ADD COLUMN kind VARCHAR(20) NOT NULL DEFAULT 'DAY_OFF';

-- reason is only meaningful for DAY_OFF; CUSTOM_HOURS rows carry no reason.
ALTER TABLE schedule_exceptions ALTER COLUMN reason DROP NOT NULL;

ALTER TABLE schedule_exceptions
    ADD CONSTRAINT chk_exc_kind CHECK (kind IN ('DAY_OFF', 'CUSTOM_HOURS'));

-- A DAY_OFF must carry a reason; a CUSTOM_HOURS must not.
ALTER TABLE schedule_exceptions
    ADD CONSTRAINT chk_exc_reason CHECK (
        (kind = 'DAY_OFF' AND reason IS NOT NULL) OR
        (kind = 'CUSTOM_HOURS' AND reason IS NULL));

-- Child intervals for CUSTOM_HOURS exceptions. Wall-clock TIME, single-calendar-day windows only.
CREATE TABLE schedule_exception_intervals (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    exception_id  UUID NOT NULL REFERENCES schedule_exceptions(id) ON DELETE CASCADE,
    start_time    TIME NOT NULL,
    end_time      TIME NOT NULL,
    CONSTRAINT chk_exc_interval_order CHECK (end_time > start_time)
);

CREATE INDEX idx_exc_intervals_exception ON schedule_exception_intervals (exception_id);
