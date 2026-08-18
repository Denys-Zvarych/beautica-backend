-- Phase salon-service-favourites — widen the favorites target_type CHECK to admit
-- SALON_SERVICE.
--
-- V92 created chk_favorite_target_type as CHECK (target_type IN ('MASTER','SALON')).
-- V134 widened it to add 'SERVICE' (the master_services.id wish-list arm). This
-- migration is a verbatim replay of that shape, adding one more value.
--
-- SALON_SERVICE identifies a favourited SALON-OWNED service_definitions.id — a single
-- UUID, no composite key, no new column. service_definitions is polymorphically owned
-- (owner_type/owner_id), so a definition id functionally determines its salon; the
-- salon is derived server-side (one join) and the client never sends it.
--
-- A composite (salonId, serviceDefId) target was rejected: uq_favorite UNIQUE
-- (client_id, target_type, target_id) (V92) dedupes on exactly those three columns, and
-- Postgres UNIQUE does not dedupe NULLs, so a nullable second id column would silently
-- break the existing MASTER/SALON/SERVICE rows' uniqueness guarantee.
--
-- No FK on target_id (unchanged from V92/V134 — the column is polymorphic across four
-- target kinds now, so a single FK remains structurally impossible). No backfill, no
-- table shape change: this migration touches only the CHECK constraint.
--
-- The shipped V92 constraint is NEVER edited (immutable migration); this fix-forward
-- migration drops and re-adds the constraint under the SAME name with the extra value.
-- Idempotent: DROP ... IF EXISTS makes a clean-DB replay deterministic. All prior values
-- are retained byte-for-byte.
ALTER TABLE favorites DROP CONSTRAINT IF EXISTS chk_favorite_target_type;

ALTER TABLE favorites
    ADD CONSTRAINT chk_favorite_target_type CHECK (
        target_type IN ('MASTER', 'SALON', 'SERVICE', 'SALON_SERVICE'));
