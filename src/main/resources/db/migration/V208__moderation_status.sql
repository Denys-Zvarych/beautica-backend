-- contract-of: V207
-- Phase 396 — UGC moderation: auto-hide on reports, support takedown, report retention.
--
-- Why contract-of V207: this file swaps V207's reporter FK (CASCADE -> SET NULL) and its unique index (full ->
-- partial). The phase-395 app's ON CONFLICT (reporter_user_id, target_type, target_id) cannot infer a PARTIAL
-- unique index, so the previous app version must not run against this schema. 395 has never shipped beyond
-- local dev, so no deployed app is affected; the header records the dependency for the gate.
--
-- 1) moderation_status on reviews + client_reviews (D1). NOT NULL with a DEFAULT, so every existing row and
--    every previous-image INSERT is 'VISIBLE'. NOT NULL also keeps the CHECK honest (a CHECK passes on NULL).
--    Partial index WHERE <> 'VISIBLE': the hidden/removed set is tiny, the visible set is everything.
--
-- 2) content_reports.reporter_user_id: ON DELETE CASCADE -> ON DELETE SET NULL, column nullable (395 audit input
--    1, option A). A hard-deleted reporter no longer takes their reports with them, so D7 retention ("kept while
--    the target exists + 1 year") holds for every report. The one-report-per-(reporter, target) unique index
--    becomes PARTIAL (WHERE reporter_user_id IS NOT NULL): orphaned rows are no longer one person's reports.
--    ContentReportRepository#insertIgnoringDuplicate names the same predicate in its ON CONFLICT arbiter.
--
-- 3) target_gone_at (D7) + TARGET_GONE resolution; burst_hold (burst guard) and resolution_note (support's
--    free-text note on a resolve). chk_content_reports_resolution is replaced BY NAME (V207 kept it stable).
--
-- 4) Queue index (395 audit input 4): the moderation queue reads OPEN rows ordered by created_at, so a partial
--    index on (created_at) WHERE status = 'OPEN' replaces V207's (status, created_at), which it makes redundant.
--
-- The ALTERs below take ACCESS EXCLUSIVE on reviews / client_reviews / content_reports and the ADD CONSTRAINT CHECKs
-- scan them: lock_timeout bounds the wait to acquire (fail fast instead of queueing every request behind us),
-- statement_timeout bounds the scans. Same values as V206.
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '5min';

-- 1) moderation_status ------------------------------------------------------------------------------------------
ALTER TABLE reviews
    ADD COLUMN moderation_status VARCHAR(16) NOT NULL DEFAULT 'VISIBLE';
ALTER TABLE reviews
    ADD CONSTRAINT chk_reviews_moderation_status
        CHECK (moderation_status IN ('VISIBLE', 'HIDDEN_PENDING', 'REMOVED'));
CREATE INDEX idx_reviews_moderation_not_visible
    ON reviews (moderation_status) WHERE moderation_status <> 'VISIBLE';

ALTER TABLE client_reviews
    ADD COLUMN moderation_status VARCHAR(16) NOT NULL DEFAULT 'VISIBLE';
ALTER TABLE client_reviews
    ADD CONSTRAINT chk_client_reviews_moderation_status
        CHECK (moderation_status IN ('VISIBLE', 'HIDDEN_PENDING', 'REMOVED'));
CREATE INDEX idx_client_reviews_moderation_not_visible
    ON client_reviews (moderation_status) WHERE moderation_status <> 'VISIBLE';

-- 2) reporter FK: SET NULL, nullable, partial unique ------------------------------------------------------------
ALTER TABLE content_reports DROP CONSTRAINT content_reports_reporter_user_id_fkey;
ALTER TABLE content_reports ALTER COLUMN reporter_user_id DROP NOT NULL;
ALTER TABLE content_reports
    ADD CONSTRAINT content_reports_reporter_user_id_fkey
        FOREIGN KEY (reporter_user_id) REFERENCES users(id) ON DELETE SET NULL;

DROP INDEX ux_content_reports_reporter_target;
-- Also the FK's referencing-side index: `reporter_user_id = $1` implies IS NOT NULL, so the planner can use it for
-- the SET NULL lookup on a user delete.
CREATE UNIQUE INDEX ux_content_reports_reporter_target
    ON content_reports (reporter_user_id, target_type, target_id) WHERE reporter_user_id IS NOT NULL;

-- 3) retention, burst guard, resolution note --------------------------------------------------------------------
ALTER TABLE content_reports ADD COLUMN target_gone_at TIMESTAMPTZ NULL;
ALTER TABLE content_reports ADD COLUMN burst_hold BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE content_reports ADD COLUMN resolution_note VARCHAR(500) NULL;

ALTER TABLE content_reports DROP CONSTRAINT chk_content_reports_resolution;
ALTER TABLE content_reports
    ADD CONSTRAINT chk_content_reports_resolution CHECK (
        resolution IS NULL OR resolution IN ('REMOVED', 'RESTORED', 'DISMISSED', 'ACTIONED', 'TARGET_GONE'));
-- A gone target is always closed: the REMOVE action and the retention job set target_gone_at together with
-- (or after) the resolution, never on an OPEN row.
ALTER TABLE content_reports
    ADD CONSTRAINT chk_content_reports_gone_resolved CHECK (target_gone_at IS NULL OR status = 'RESOLVED');
-- The burst guard only ever holds REVIEW reports (the only auto-hidable target).
ALTER TABLE content_reports
    ADD CONSTRAINT chk_content_reports_burst_review CHECK (NOT burst_hold OR target_type = 'REVIEW');
ALTER TABLE content_reports
    ADD CONSTRAINT chk_content_reports_note_resolved CHECK (resolution_note IS NULL OR status = 'RESOLVED');

-- D7 purge: DELETE ... WHERE target_gone_at < now() - 1 year.
CREATE INDEX idx_content_reports_target_gone
    ON content_reports (target_gone_at) WHERE target_gone_at IS NOT NULL;

-- 4) queue index ------------------------------------------------------------------------------------------------
DROP INDEX idx_content_reports_status_created;
CREATE INDEX idx_content_reports_open_created
    ON content_reports (created_at) WHERE status = 'OPEN';
