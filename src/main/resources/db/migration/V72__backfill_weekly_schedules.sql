-- Phase 15.1 — Master Schedule Schema: backfill legacy working_hours into the new model.
-- Pure SQL data migration, idempotent-by-construction (Flyway runs it exactly once).
-- NON-DESTRUCTIVE: working_hours is NOT dropped here — it stays read-intact until a later cleanup
-- phase confirms 15.4–15.5 are verified in prod.

-- 1. One open-ended "legacy" weekly_schedule per master that has any working_hours rows.
--    valid_from is pinned to a documented epoch so today (and any past date) is covered;
--    valid_to NULL = open-ended legacy window.
INSERT INTO weekly_schedules (master_id, valid_from, valid_to)
SELECT DISTINCT wh.master_id, DATE '2020-01-01', NULL::date
FROM working_hours wh;

-- 2. One working_interval per ACTIVE legacy working_hours row, mapped 1:1 into that master's
--    legacy schedule. Inactive days (is_active = false) produce no interval row.
INSERT INTO working_intervals (schedule_id, day_of_week, start_time, end_time)
SELECT ws.id, wh.day_of_week, wh.start_time, wh.end_time
FROM working_hours wh
JOIN weekly_schedules ws ON ws.master_id = wh.master_id
WHERE wh.is_active = TRUE;

-- 3. Mark the legacy table deprecated. Read path migrates to working_intervals in Phase 15.4;
--    retained for rollback until a dedicated cleanup phase.
COMMENT ON TABLE working_hours IS
    'DEPRECATED (Phase 15.1) — read path migrated to working_intervals in Phase 15.4; retained for rollback. Do not write.';
