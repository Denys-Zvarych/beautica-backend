-- non-destructive
-- Reason: validates the four V219 NOT VALID CHECKs, which every existing row satisfies (V219 only added literals and branches for new types, and moderated_target_type is NULL on every older row), and adds a plain non-unique index.
-- Phase 415 audit-fix cycle 1 (PERF) — VALIDATE half of the V219 NOT VALID / VALIDATE split (V212 + V214 house style).
-- VALIDATE CONSTRAINT takes SHARE UPDATE EXCLUSIVE on in_app_notification: the scan blocks neither readers nor
-- writers, unlike an inline-validated ADD CONSTRAINT, which scans while holding ACCESS EXCLUSIVE. Each statement is
-- its own scan.
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '5min';

ALTER TABLE in_app_notification VALIDATE CONSTRAINT in_app_notification_type_chk;
ALTER TABLE in_app_notification VALIDATE CONSTRAINT in_app_notification_shape_chk;
ALTER TABLE in_app_notification VALIDATE CONSTRAINT chk_inapp_moderated_target_type;
ALTER TABLE in_app_notification VALIDATE CONSTRAINT in_app_notification_dedup_key_chk;

-- GET /api/v1/reports/mine: every report of one reporter, newest first, LIMIT 1000. Matching the ORDER BY lets the
-- planner read the newest rows in index order and stop at the LIMIT (no per-call sort); INCLUDE makes it index-only
-- for the two returned columns. content_reports is small (rate-limited intake, one-year retention), so a plain
-- CREATE INDEX inside the migration transaction is fine.
CREATE INDEX idx_content_reports_reporter_created
    ON content_reports (reporter_user_id, created_at DESC, id DESC)
    INCLUDE (target_type, target_id)
    WHERE reporter_user_id IS NOT NULL;
