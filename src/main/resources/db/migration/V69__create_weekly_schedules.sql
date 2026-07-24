-- Phase 15.1 — Master Schedule Schema.
-- A weekly_schedule is the "active window" for a master's recurring weekly template.
-- valid_from/valid_to bound the date range the template applies to; valid_to NULL = open-ended
-- (used by the legacy backfill in V72 and by "no end set" templates).
--
-- NOTE: No DB-level overlap-exclusion constraint in this phase. Preventing two schedules of the
-- same master from overlapping requires a GiST daterange EXCLUDE constraint with COALESCE/infinity
-- handling for the NULL valid_to case, which is error-prone. Overlap is enforced in the service
-- layer (Phase 15.4). Follow-up option: add
--   EXCLUDE USING gist (master_id WITH =, daterange(valid_from, valid_to, '[]') WITH &&)
-- once the NULL-bound semantics are pinned down.
CREATE TABLE weekly_schedules (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    master_id   UUID NOT NULL REFERENCES masters(id) ON DELETE CASCADE,
    valid_from  DATE NOT NULL,
    valid_to    DATE,                       -- NULL = open-ended (legacy backfill / "no end set")
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_validity_order CHECK (valid_to IS NULL OR valid_to >= valid_from)
);

CREATE INDEX idx_weekly_schedules_master_window ON weekly_schedules (master_id, valid_from, valid_to);

COMMENT ON TABLE weekly_schedules IS
    'Phase 15.1: active-window weekly template per master. valid_to NULL = open-ended. '
    'Per-master schedule overlap is enforced in the service layer (Phase 15.4), not via a DB GiST exclusion.';
