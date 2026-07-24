-- Drop the reason and note columns from schedule_exceptions entirely.
--
-- Origin: V4 created schedule_exceptions with `reason VARCHAR(50) NOT NULL` (+ enum CHECK
--         chk_exception_reason) and `note TEXT`.
-- Interim: V71 dropped reason NOT NULL and added the kind/reason consistency CHECK chk_exc_reason;
--          V82 relaxed chk_exc_reason so DAY_OFF.reason became OPTIONAL.
--
-- Product decision (2026-06): a day-off carries NO reason and NO note. Both fields are redundant and
-- are removed from the schema. This is a DESTRUCTIVE change — any existing reason/note data is dropped.
-- The `kind` discriminator (DAY_OFF vs CUSTOM_HOURS) and chk_exc_kind are RETAINED.
--
-- Two constraints reference the reason column and must be dropped first:
--   - chk_exc_reason       (V71, relaxed V82) — kind/reason consistency
--   - chk_exception_reason (V4)               — reason enum-value whitelist
--
-- Fail fast instead of head-of-line-blocking the table if a long-open transaction
-- holds a weak lock on schedule_exceptions at deploy time (perf audit hardening).
SET lock_timeout = '3s';

ALTER TABLE schedule_exceptions DROP CONSTRAINT IF EXISTS chk_exc_reason;
ALTER TABLE schedule_exceptions DROP CONSTRAINT IF EXISTS chk_exception_reason;

ALTER TABLE schedule_exceptions DROP COLUMN IF EXISTS reason;
ALTER TABLE schedule_exceptions DROP COLUMN IF EXISTS note;
