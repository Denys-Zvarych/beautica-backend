-- Fail the deploy fast instead of stalling every write on the busiest tables.
--
-- Flyway wraps this whole file in ONE transaction, so every lock is held SIMULTANEOUSLY until
-- COMMIT. Measured, not assumed: `ALTER TABLE ... DROP CONSTRAINT` (there are THREE below) takes
-- ACCESS EXCLUSIVE on the referencing table AND on the referenced `users` — so this migration holds
-- ACCESS EXCLUSIVE on `masters`, `bookings`, `appointments` AND `users` until COMMIT, which blocks
-- every login and every JWT-backed read for its whole duration; schedule the deploy accordingly.
-- (The FK re-creations take only SHARE ROW EXCLUSIVE, which the DROPs' stronger lock subsumes.)
--
-- The work itself is sub-second (no table rewrite: DROP NOT NULL is catalog-only, ADD COLUMN ...
-- NULL is catalog-only, and the new CHECK/FKs validate against a small table). Lock ACQUISITION is
-- the unbounded part: one long-running transaction holding a conflicting lock on `bookings` parks
-- the ALTER in the lock queue, and because a pending ACCESS EXCLUSIVE request blocks every
-- lock request behind it, ordinary reads and writes queue up behind the migration for as long
-- as that transaction lives. With a 3s ceiling the migration instead raises
-- "canceling statement due to lock timeout", Flyway rolls the whole file back (it is
-- transactional), the deploy fails loudly, and production keeps serving traffic — retry when
-- the blocking transaction is gone.
--
-- statement_timeout as well as lock_timeout (V137:23, V138:45, V141:57): lock_timeout bounds only
-- lock ACQUISITION, never the work done WHILE the lock is held — and section 5 re-adds an FK on
-- `bookings`, i.e. a validation scan of the largest table. Read the unit literally (V120:42,
-- V121:47, V137:28): statement_timeout applies PER statement and is re-armed for each, so 1min is
-- the per-ALTER ceiling here, not a whole-file one.
--
-- SET LOCAL: scoped to Flyway's per-migration transaction, so neither value leaks to the pooled
-- connection once this migration commits. A bare `SET` — which this file first shipped — DOES leak:
-- under the `test` profile Flyway borrows the main Hikari pool (FlywayDataSourceConfig is
-- @Profile("!test")), and Hikari resets autoCommit/isolation/readOnly/catalog only, never GUCs, so
-- one pooled connection would carry lock_timeout='3s' for the rest of the run and fail a contended
-- pg_advisory_xact_lock booking test with 55P03 instead of waiting for the lock.
--
-- Do NOT remove these two lines, and do not raise them materially: the recovery for a timeout is a
-- re-run, the recovery for a lock storm is an incident.
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '1min';

-- =============================================================================
-- Phase 294 — Detachable master + staff-delete FK relaxation
--
-- docs/backend-phases/phase-294-detachable-master-and-staff-delete-fk-relaxation.md
--
-- WHAT THIS MIGRATION DOES, AND WHAT IT DELIBERATELY DOES NOT.
-- It is ADDITIVE and PERMISSIVE only (phase 294 D6). Not one existing row changes,
-- and no application code writes any column added here: every live master keeps
-- user_id NOT NULL in practice and the new CHECK passes trivially for all of them.
-- Phase 295 is what actually detaches a master and hard-deletes a staff users row;
-- this migration only makes that legal.
--
-- WHY (the 2026-09-04 reversal). The user reversed the deactivate-the-staff rule
-- recorded under phase 267 D1:
--   "1. deleting one salon should DELETE salon staff, close bookings future and send
--    notifications/sms 2. deleted salon staff should be easy invited again because
--    they shouldn't exist in our DB"
-- A plain DELETE FROM users is refused by Postgres today. masters.user_id
-- (V4__Patch_salons_add_masters.sql:12, UNIQUE NOT NULL REFERENCES users(id) with no
-- on-delete clause) blocks deleting ANY staff user, always — a masters row always
-- exists for one. bookings.created_by_user_id (V137) and
-- appointments.created_by_user_id (V139) block it for anyone who ever rang up a
-- walk-in. This migration relaxes exactly those three.
--
-- THE TWO REPRESENTABLE STATES (phase 294 D4). After this migration a masters row is
-- either ATTACHED (user_id IS NOT NULL, detached_at IS NULL — every row today) or
-- DETACHED (user_id IS NULL, detached_at IS NOT NULL, detached_first_name IS NOT
-- NULL). chk_masters_detachment_coherent makes a half-detached row — user_id nulled
-- with no name snapshot, i.e. a nameless provider on a client's own past receipt —
-- UNWRITABLE, not merely unlikely.
--
-- !! ORDER OF OPERATIONS FOR THE PHASE 295 DELETE — READ THIS BEFORE WRITING IT !!
-- The detach must be ONE UPDATE that writes the snapshot AND nulls user_id together,
-- and it must run BEFORE the users row is deleted:
--
--   1) UPDATE masters SET user_id = NULL, detached_first_name = ..., detached_last_name = ...,
--             detached_at = ..., is_active = false WHERE id = ...;
--   2) DELETE FROM users WHERE id = ...;      -- now unblocked, nothing references the row
--
-- The FK's ON DELETE SET NULL below CANNOT do step 1 on its own. It writes exactly one
-- column, and the row it leaves behind satisfies neither arm of
-- chk_masters_detachment_coherent: detached_at is still NULL, and it could not have been
-- pre-set, because while the master is attached the first arm REQUIRES detached_at IS NULL.
-- Postgres cannot defer a CHECK constraint either (only UNIQUE / PK / FK / EXCLUDE are
-- deferrable), so no in-transaction ordering rescues a bare DELETE.
--
-- That is deliberate, not an oversight: it makes losing the name stub impossible. What the
-- relaxed FK buys is that masters.user_id no longer REFUSES the delete outright — the only
-- thing that can now reject it is the coherence rule, which the caller controls by
-- snapshotting first. Do NOT weaken the CHECK to make a one-step delete work.
-- Pinned by MasterDetachmentContractIT case 5.
-- =============================================================================

-- ── 1. the detachment snapshot columns (phase 294 D2) ───────────────────────────
-- Master has no name of its own; the name comes from Master#user (@OneToOne). These
-- three columns are the snapshot taken at detach time and NEVER kept in sync
-- afterwards: there is no trigger and no sync-on-profile-update, so there is no drift
-- hazard. A live master's name always comes from `users`; a detached master's always
-- comes from here. Length 100 mirrors users.first_name / users.last_name.
ALTER TABLE masters
    ADD COLUMN detached_first_name VARCHAR(100),
    ADD COLUMN detached_last_name  VARCHAR(100),
    ADD COLUMN detached_at         TIMESTAMPTZ;

COMMENT ON COLUMN masters.detached_first_name IS
    'Phase 294 (2026-09-04 reversal of 267 D1). First name snapshotted at the moment the '
    'staff users row was hard-deleted. NULL for every attached master — read Master#displayFirstName(), '
    'never this column directly. Written only by the phase 295 detach path; never synced afterwards.';

COMMENT ON COLUMN masters.detached_last_name IS
    'Phase 294. Last name snapshotted at detach time. NULL for every attached master, and legitimately '
    'NULL on a detached row whose users.last_name was itself null — only detached_first_name is '
    'required by chk_masters_detachment_coherent. Read Master#displayLastName().';

COMMENT ON COLUMN masters.detached_at IS
    'Phase 294. When this masters row was detached from its (now-deleted) users row. NULL <=> attached. '
    'This column, not user_id alone, is the state discriminator in chk_masters_detachment_coherent.';

-- ── 2 + 3. user_id becomes nullable, FK recreated ON DELETE SET NULL (D1) ───────
-- Postgres allows unlimited NULLs under a UNIQUE constraint, so N detached masters
-- coexist under masters_user_id_key with no partial index needed. The UNIQUE
-- constraint itself is untouched.
ALTER TABLE masters ALTER COLUMN user_id DROP NOT NULL;

-- V4 declared the FK inline (`user_id UUID UNIQUE NOT NULL REFERENCES users(id)`), so
-- Postgres auto-named it. Resolve the name from the catalog rather than hardcoding
-- masters_user_id_fkey: an environment restored from a dump taken through a different
-- tool can carry a different auto-name, and a hardcoded DROP would crash-loop Flyway
-- there.
DO $$
DECLARE
    fk_name TEXT;
BEGIN
    SELECT con.conname INTO fk_name
      FROM pg_constraint con
      JOIN pg_class rel ON rel.oid = con.conrelid
      JOIN pg_attribute att ON att.attrelid = con.conrelid AND att.attnum = con.conkey[1]
     WHERE rel.relname = 'masters'
       AND con.contype = 'f'
       AND array_length(con.conkey, 1) = 1
       AND att.attname = 'user_id';

    IF fk_name IS NULL THEN
        RAISE EXCEPTION 'V157: no single-column FK found on masters.user_id — refusing to continue';
    END IF;

    EXECUTE format('ALTER TABLE masters DROP CONSTRAINT %I', fk_name);
END $$;

ALTER TABLE masters
    ADD CONSTRAINT fk_masters_user_id
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL;

COMMENT ON CONSTRAINT fk_masters_user_id ON masters IS
    'Phase 294 (2026-09-04). Was V4''s inline UNIQUE NOT NULL REFERENCES users(id) with NO on-delete '
    'clause — the fifth and decisive constraint blocking a staff users hard-delete, and the only one '
    'that blocked it ALWAYS (a masters row exists for every staff member). Now ON DELETE SET NULL: '
    'deleting the users row detaches the masters row instead of being refused, so a client''s own past '
    'booking can still name who performed the service. NOTE the SET NULL cannot perform a detach on '
    'its own — it writes one column and the result fails chk_masters_detachment_coherent. The caller '
    'must null user_id and write the name snapshot in ONE UPDATE, then DELETE the user. See this '
    'file''s header block.';

-- ── 4. the two-states CHECK (D4) ────────────────────────────────────────────────
-- detached_last_name is deliberately NOT required: users.last_name is nullable, so a
-- detached row faithfully snapshotting a null surname must stay writable. A first
-- name is required because it is what the client's receipt actually renders.
ALTER TABLE masters
    ADD CONSTRAINT chk_masters_detachment_coherent CHECK (
        (user_id IS NOT NULL AND detached_at IS NULL)
     OR (user_id IS NULL     AND detached_at IS NOT NULL
         AND detached_first_name IS NOT NULL)
    );

COMMENT ON CONSTRAINT chk_masters_detachment_coherent ON masters IS
    'Phase 294 D4. ATTACHED and DETACHED are the only two representable states. A half-detached row '
    '(user_id nulled without a name snapshot) would render a NAMELESS provider on a client''s own past '
    'booking; this makes that unwritable rather than merely unlikely. Passes trivially for every row '
    'that existed before V157.';

-- ── 5. bookings.created_by_user_id: RESTRICT -> SET NULL (D5) ───────────────────
-- V137:129 argued RESTRICT because "this column exists for ATTRIBUTION, and SET NULL
-- makes that attribution destructible". THAT RATIONALE IS SUPERSEDED, BY NAME, HERE.
-- It was written when nothing in the codebase deleted a user ("RESTRICT matches the
-- codebase's actual lifecycle, which is deactivate-never-delete") and it explicitly
-- deferred the case to "a future right-to-erasure flow [that] MUST handle this column
-- explicitly". The 2026-09-04 reversal IS that flow. The attribution being relaxed is
-- "which staff member of this now-deleted salon rang up this walk-in" — a fact about
-- a salon that no longer exists, whose own account the user has ordered deleted.
DO $$
DECLARE
    fk_name TEXT;
BEGIN
    SELECT con.conname INTO fk_name
      FROM pg_constraint con
      JOIN pg_class rel ON rel.oid = con.conrelid
      JOIN pg_attribute att ON att.attrelid = con.conrelid AND att.attnum = con.conkey[1]
     WHERE rel.relname = 'bookings'
       AND con.contype = 'f'
       AND array_length(con.conkey, 1) = 1
       AND att.attname = 'created_by_user_id';

    IF fk_name IS NULL THEN
        RAISE EXCEPTION 'V157: no single-column FK found on bookings.created_by_user_id';
    END IF;

    EXECUTE format('ALTER TABLE bookings DROP CONSTRAINT %I', fk_name);
END $$;

ALTER TABLE bookings
    ADD CONSTRAINT fk_bookings_created_by
        FOREIGN KEY (created_by_user_id) REFERENCES users(id) ON DELETE SET NULL;

COMMENT ON CONSTRAINT fk_bookings_created_by ON bookings IS
    'Phase 294 D5 (2026-09-04). Relaxed from V137''s ON DELETE RESTRICT, whose stated rationale '
    '(V137:129, "this column exists for ATTRIBUTION and SET NULL makes that a lie") is superseded here: '
    'it was written when no code path deleted a user, and it deferred exactly this case to a future '
    'erasure flow. Do NOT revert to RESTRICT — it would re-block the staff hard-delete this whole track '
    'exists to enable.';

-- ── 6. appointments.created_by_user_id: RESTRICT -> SET NULL (D5) ──────────────
-- V139:60 carried V137's argument verbatim; it is superseded on identical grounds.
DO $$
DECLARE
    fk_name TEXT;
BEGIN
    SELECT con.conname INTO fk_name
      FROM pg_constraint con
      JOIN pg_class rel ON rel.oid = con.conrelid
      JOIN pg_attribute att ON att.attrelid = con.conrelid AND att.attnum = con.conkey[1]
     WHERE rel.relname = 'appointments'
       AND con.contype = 'f'
       AND array_length(con.conkey, 1) = 1
       AND att.attname = 'created_by_user_id';

    IF fk_name IS NULL THEN
        RAISE EXCEPTION 'V157: no single-column FK found on appointments.created_by_user_id';
    END IF;

    EXECUTE format('ALTER TABLE appointments DROP CONSTRAINT %I', fk_name);
END $$;

ALTER TABLE appointments
    ADD CONSTRAINT fk_appointments_created_by
        FOREIGN KEY (created_by_user_id) REFERENCES users(id) ON DELETE SET NULL;

COMMENT ON CONSTRAINT fk_appointments_created_by ON appointments IS
    'Phase 294 D5 (2026-09-04). Relaxed from V139''s ON DELETE RESTRICT (V139:60, which carried '
    'V137:129''s attribution argument verbatim). Superseded on identical grounds — see the matching '
    'comment on fk_bookings_created_by. Header and children must never diverge on this clause.';
