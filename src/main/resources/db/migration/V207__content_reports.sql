-- non-destructive
-- Reason: new table created in this file; its CHECKs and unique index constrain only rows written by this release.
-- Phase 395 — UGC report intake (Google Play UGC policy: "in-app system for reporting objectionable UGC").
--
-- One row per (reporter, target). The row is the source of truth; the support e-mail sent after commit is
-- lossy by design (phase-395 D7) and carries ids only. Phase 396 adds moderation actions on top of this table
-- (resolve REMOVE / RESTORE / DISMISS, the TARGET_GONE resolution and target_gone_at) — it replaces
-- chk_content_reports_resolution by name, so keep that name stable.
--
-- target_id is a bare UUID with NO foreign key: it points at one of five different tables (reviews,
-- client_reviews, media_files, masters, salons) or at users (CLIENT_PROFILE), chosen by target_type. Postgres
-- cannot express a polymorphic FK, so existence/visibility is checked in ReportTargetVisibilityResolver at
-- intake and by the phase-396 retention job afterwards. comment is the reporter's free text (PII-adjacent):
-- it stays in this table and never reaches the e-mail or the logs.
--
-- reporter_user_id ON DELETE CASCADE: a hard-deleted account takes its own reports with it (no blob
-- column here, so no external-storage sweep is needed — §O-8 does not apply).
--
-- status/target_type/reason are NOT NULL so their CHECKs are not vacuous (a CHECK passes on NULL).
-- chk_content_reports_resolution_state ties the nullable resolution columns to status so a row can never be
-- RESOLVED without a resolution, or OPEN with one.
CREATE TABLE content_reports (
    id                UUID         PRIMARY KEY,
    reporter_user_id  UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    target_type       VARCHAR(32)  NOT NULL,
    target_id         UUID         NOT NULL,
    reason            VARCHAR(32)  NOT NULL,
    comment           VARCHAR(500) NULL,
    status            VARCHAR(16)  NOT NULL DEFAULT 'OPEN',
    resolution        VARCHAR(16)  NULL,
    resolved_at       TIMESTAMPTZ  NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_content_reports_target_type CHECK (target_type IN (
        'REVIEW', 'CLIENT_REVIEW', 'PORTFOLIO_PHOTO', 'MASTER_PROFILE', 'SALON_PROFILE', 'CLIENT_PROFILE')),
    CONSTRAINT chk_content_reports_reason CHECK (reason IN (
        'SPAM', 'OFFENSIVE', 'HARASSMENT', 'FALSE_INFO', 'SEXUAL_CONTENT', 'OTHER')),
    CONSTRAINT chk_content_reports_status CHECK (status IN ('OPEN', 'RESOLVED')),
    CONSTRAINT chk_content_reports_resolution CHECK (
        resolution IS NULL OR resolution IN ('REMOVED', 'RESTORED', 'DISMISSED', 'ACTIONED')),
    CONSTRAINT chk_content_reports_resolution_state CHECK (
        (status = 'OPEN' AND resolution IS NULL AND resolved_at IS NULL)
        OR (status = 'RESOLVED' AND resolution IS NOT NULL AND resolved_at IS NOT NULL)),
    -- OTHER needs a reporter comment of at least 5 characters (phase-395 D3); the service enforces the same
    -- rule on the trimmed text, this keeps a raw-SQL write honest too.
    CONSTRAINT chk_content_reports_other_comment CHECK (
        reason <> 'OTHER' OR (comment IS NOT NULL AND char_length(btrim(comment)) >= 5))
);

-- D4: one report per (reporter, target). Also the ON CONFLICT arbiter of ContentReportRepository#insertIgnoringDuplicate,
-- which makes a concurrent double POST collapse onto one row.
CREATE UNIQUE INDEX ux_content_reports_reporter_target
    ON content_reports (reporter_user_id, target_type, target_id);

-- Phase-396 moderation queue: GET /internal/moderation/reports?status=OPEN ordered by created_at.
CREATE INDEX idx_content_reports_status_created ON content_reports (status, created_at);

-- Phase-396 auto-hide count and "resolve all OPEN reports on the same target".
CREATE INDEX idx_content_reports_target ON content_reports (target_type, target_id);
