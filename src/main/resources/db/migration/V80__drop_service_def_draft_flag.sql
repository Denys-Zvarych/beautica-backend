-- V80 (Phase 16.9 reversal -> Option A, catalog-only): drop the now-dead draft surface.
--
-- Background
--   Approval of a service-type suggestion is now CATALOG-ONLY: it creates ONLY a ServiceType,
--   never a draft ServiceDefinition / MasterServiceAssignment (fix d50a08c on
--   fix/service-type-promotion-catalog-only). Nothing in production ever sets
--   service_definitions.is_draft = TRUE, so the entire draft-display surface is dead code.
--   This migration reverses the draft half of V78/V79 and returns the schema to its
--   pre-draft state.
--
-- What this migration does
--   1. DROP COLUMN service_definitions.is_draft (added by V78 Part 1).
--   2. Restore the STRICT duration CHECK that V79 relaxed. V79 replaced the V6 constraint
--        chk_service_def_base_duration CHECK (base_duration_minutes > 0)
--      with the draft-aware
--        CHECK (is_draft OR base_duration_minutes > 0).
--      With is_draft gone, the relaxed predicate references a missing column; this migration
--      drops it and re-adds the original V6 invariant verbatim.
--
-- What this migration KEEPS (still live under catalog-only promotion)
--   * platform_categories.service_category_id bucket-map column + FK + V78 backfill — the
--     catalog-only promotion resolves the System-B -> System-A bucket through it. Untouched.
--
-- Ordering / dependency
--   The duration CHECK must be dropped (it references is_draft) BEFORE the column is dropped,
--   otherwise Postgres refuses to drop a column a CHECK still depends on. Drop the constraint,
--   drop the column, then re-add the strict constraint.
--
-- Safety / idempotency
--   * DROP COLUMN IF EXISTS / DROP CONSTRAINT IF EXISTS make a clean-DB replay / re-apply a
--     no-op (matches V73/V74/V78/V79 convention).
--   * NOT VALID + VALIDATE keeps the table available during the full-table re-scan
--     (ShareUpdateExclusiveLock, not AccessExclusiveLock) — same pattern as V79.
--   * V80 is the next free version (latest applied = V79). Applied migrations are immutable;
--     this is a forward-only, fix-forward migration — V78/V79 are NOT edited.
--   * No locality data — occupied-territory ban N/A.

-- Step 1: drop the relaxed (is_draft-aware) CHECK first — it depends on the is_draft column.
ALTER TABLE service_definitions
    DROP CONSTRAINT IF EXISTS chk_service_def_base_duration;

-- Step 2: drop the dead draft marker column.
ALTER TABLE service_definitions
    DROP COLUMN IF EXISTS is_draft;

-- Step 2.5: remove legacy Option-B draft sentinels (base_duration_minutes = 0) that V79's
-- relaxed CHECK permitted, so the strict chk_service_def_base_duration (> 0) re-added below can
-- re-validate without aborting Flyway. Pre-launch these sentinel-priced, catalog-only-era drafts
-- are the only rows with base_duration_minutes <= 0 — never real published services — so this
-- delete is safe. FK order: assignments first (master_services -> service_definitions), then the
-- definitions. The <= 0 predicate is naturally idempotent (a clean-DB replay sweeps zero rows).
DELETE FROM master_services
 WHERE service_def_id IN (
   SELECT id FROM service_definitions WHERE base_duration_minutes <= 0);

DELETE FROM service_definitions
 WHERE base_duration_minutes <= 0;

-- Step 3: re-add the original strict V6 invariant — every service requires a real duration.
ALTER TABLE service_definitions
    ADD CONSTRAINT chk_service_def_base_duration
        CHECK (base_duration_minutes > 0) NOT VALID;

ALTER TABLE service_definitions
    VALIDATE CONSTRAINT chk_service_def_base_duration;

COMMENT ON CONSTRAINT chk_service_def_base_duration ON service_definitions IS
    'Every service requires base_duration_minutes > 0 (restored from V6 after the Phase 16.9 draft surface was removed in V80).';
