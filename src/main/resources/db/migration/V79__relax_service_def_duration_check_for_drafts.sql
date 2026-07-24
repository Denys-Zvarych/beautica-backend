-- V79 (Phase 16.9 CRITICAL fix): relax chk_service_def_base_duration so a DRAFT may carry
-- the 0-minute "unset" sentinel.
--
-- Background
--   V78 added is_draft and persists a promotion draft with sentinel placeholders
--   (base_price = 0, price_type = 'FIXED', base_duration_minutes = 0). Its header reasoned
--   ONLY about chk_service_def_price_mode (which a ₴0 FIXED row satisfies) and was silent on
--   the duration CHECK. chk_service_def_base_duration from V6 still requires
--   base_duration_minutes > 0, so EVERY master-attributed approval threw
--   DataIntegrityViolationException and rolled back the whole approval transaction.
--
-- Fix (gate the invariant on draft state)
--   A draft means "not configured yet", so 0 is the correct unset sentinel (consistent with
--   the ₴0 FIXED price sentinel). Relax the CHECK to allow 0 ONLY for drafts:
--       CHECK (is_draft OR base_duration_minutes > 0)
--   A non-draft service STILL requires base_duration_minutes > 0 — so when a draft is later
--   completed and is_draft flips to false, a real duration MUST be set. The > 0 invariant is
--   preserved for every real (bookable) service; only the unset draft sentinel is permitted.
--
--   The price CHECK (chk_service_def_price_mode, V78) already accepts the ₴0 FIXED draft
--   (FIXED requires base_price IS NOT NULL with no > 0 floor), so it needs no change here.
--
-- Safety / idempotency
--   * Forward-only, additive. DROP ... IF EXISTS then ADD makes a clean-DB replay / re-apply
--     deterministic (matches V73/V74/V78 convention).
--   * is_draft is BOOLEAN NOT NULL DEFAULT FALSE (V78), so (is_draft OR ...) never short-
--     circuits on NULL.
--   * NOT VALID + VALIDATE keeps the table available during the full-table re-scan
--     (ShareUpdateExclusiveLock, not AccessExclusiveLock) — same pattern as V67/V78.
--   * V79 is the next free version (latest applied = V78). Applied migrations are immutable.
--   * No locality data — occupied-territory ban N/A.

ALTER TABLE service_definitions
    DROP CONSTRAINT IF EXISTS chk_service_def_base_duration;

ALTER TABLE service_definitions
    ADD CONSTRAINT chk_service_def_base_duration
        CHECK (is_draft OR base_duration_minutes > 0) NOT VALID;

ALTER TABLE service_definitions
    VALIDATE CONSTRAINT chk_service_def_base_duration;

COMMENT ON CONSTRAINT chk_service_def_base_duration ON service_definitions IS
    'Real (non-draft) services require base_duration_minutes > 0; drafts may carry the 0-minute unset sentinel (Phase 16.9).';
