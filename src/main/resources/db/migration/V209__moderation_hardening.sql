-- non-destructive
-- Reason: chk_content_reports_orphan_no_comment only constrains reporter-less rows, which the UPDATE below normalises first and no app version inserts (the FK action makes them, and the trigger keeps them compliant); the replaced other-comment CHECK is strictly looser than V207's.
-- Phase 396 audit-fix (cycle 1) — moderation hardening on top of V208. Structural, so a new version: V208 is already
-- applied on local dev DBs.
--
-- 1) reviews.auto_hidden_at (D16): stamped when an auto-hide moves a review to HIDDEN_PENDING. The per-provider cap
--    (app.moderation.auto-hide-cap, default 1 per 24 h) counts the provider's reviews with auto_hidden_at in the
--    window. Partial index: the stamped set is tiny, the count is a range over it.
--
-- 2) idx_reviews_moderation_not_visible / idx_client_reviews_moderation_not_visible (V208) dropped: no query reads
--    the non-VISIBLE set by status alone (public reads filter = 'VISIBLE' on top of master/salon indexes; the queue
--    loads reviews by primary key), so they were write cost with no reader.
--
-- 3) RESOLVED queue index (D17): GET /internal/moderation/reports?status=RESOLVED is newest RESOLUTION first
--    (resolved_at DESC, id DESC as the tie-break for reports closed together). Partial, mirrors the OPEN index.
--
-- 4) Orphaned reports keep no reporter text (D18): when a reporter account is deleted, ON DELETE SET NULL (V208)
--    nulls reporter_user_id and the BEFORE UPDATE trigger below clears comment in the same row write, for every
--    deletion path. chk_content_reports_other_comment is relaxed for reporter-less rows (an OTHER report keeps its
--    reason, loses the text); chk_content_reports_orphan_no_comment pins the invariant for raw-SQL writes too.

-- ALTER TABLE takes ACCESS EXCLUSIVE and ADD CONSTRAINT CHECK scans: bound the wait and the scan (same as V206/V208).
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '5min';

-- 1) auto-hide cap ----------------------------------------------------------------------------------------------
ALTER TABLE reviews ADD COLUMN auto_hidden_at TIMESTAMPTZ NULL;
CREATE INDEX idx_reviews_auto_hidden_at
    ON reviews (auto_hidden_at) WHERE auto_hidden_at IS NOT NULL;

-- 2) unused indexes ---------------------------------------------------------------------------------------------
DROP INDEX idx_reviews_moderation_not_visible;
DROP INDEX idx_client_reviews_moderation_not_visible;

-- 3) RESOLVED queue index ---------------------------------------------------------------------------------------
CREATE INDEX idx_content_reports_resolved_at
    ON content_reports (resolved_at DESC, id DESC) WHERE status = 'RESOLVED';

-- 4) orphaned reports keep no comment ---------------------------------------------------------------------------
-- Drop V207's other-comment CHECK BEFORE the UPDATE: an orphaned OTHER report (reporter deleted under V208's
-- SET NULL) still carries its >= 5-char comment, and nulling it under the old CHECK would abort the migration.
ALTER TABLE content_reports DROP CONSTRAINT chk_content_reports_other_comment;

UPDATE content_reports SET comment = NULL WHERE reporter_user_id IS NULL AND comment IS NOT NULL;

ALTER TABLE content_reports
    ADD CONSTRAINT chk_content_reports_other_comment CHECK (
        reason <> 'OTHER' OR reporter_user_id IS NULL OR (comment IS NOT NULL AND char_length(btrim(comment)) >= 5));
ALTER TABLE content_reports
    ADD CONSTRAINT chk_content_reports_orphan_no_comment CHECK (reporter_user_id IS NOT NULL OR comment IS NULL);

CREATE FUNCTION content_reports_clear_orphan_comment() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    NEW.comment := NULL;
    RETURN NEW;
END;
$$;

-- Fires for the referential SET NULL action as well (it is an UPDATE of reporter_user_id on this table), so every
-- user-deletion path is covered without a service-layer sweep.
CREATE TRIGGER trg_content_reports_clear_orphan_comment
    BEFORE UPDATE OF reporter_user_id ON content_reports
    FOR EACH ROW
    WHEN (NEW.reporter_user_id IS NULL AND OLD.reporter_user_id IS NOT NULL)
    EXECUTE FUNCTION content_reports_clear_orphan_comment();
