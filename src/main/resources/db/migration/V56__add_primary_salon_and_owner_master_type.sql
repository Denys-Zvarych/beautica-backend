-- Phase 12.1 — Owner-as-Master: primary salon + SALON_OWNER master type.
--
-- (1) salons.is_primary marks the salon created during SALON_OWNER registration.
--     The owner is automatically made a master only in their primary salon.
--     Additional salons the owner adds later are not primary and have no auto-master.
--
-- (2) Partial unique index enforces the business rule: exactly one primary salon per owner.
--
-- (3) masters partial index backs the owner-master lookup in Phases 12.2 / 12.4 and
--     the dashboard owner-scope join. No backfill — existing rows keep master_type as-is.

-- (1) Add is_primary to salons
ALTER TABLE salons
    ADD COLUMN IF NOT EXISTS is_primary BOOLEAN NOT NULL DEFAULT false;

-- (2) One primary salon per owner
CREATE UNIQUE INDEX IF NOT EXISTS idx_salons_owner_primary
    ON salons (owner_id)
    WHERE is_primary = true;

-- (3) Owner-master lookup index on masters
CREATE INDEX IF NOT EXISTS idx_masters_salon_owner_active
    ON masters (salon_id, user_id)
    WHERE master_type = 'SALON_OWNER' AND is_active = true;

-- (4) Each user may have at most one SALON_OWNER-type master row.
--     Backs the one-per-user invariant documented in MasterType.SALON_OWNER comment.
CREATE UNIQUE INDEX IF NOT EXISTS idx_masters_user_owner_type
    ON masters (user_id)
    WHERE master_type = 'SALON_OWNER';
