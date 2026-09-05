-- V150: promote salons.city_id to NOT NULL — "a salon must always have a city".
--
-- Every write path already enforces this at the application layer:
-- LocalityWriteValidator.validateProviderLocality() is called unconditionally from
-- SalonService#createSalon (line ~95) and #updateSalon (line ~250), throwing a 400
-- BusinessException ("City is required") before any INSERT/UPDATE reaches the DB. So no NEW
-- salon can be persisted with a null city_id since that guard shipped in Phase 10.6.
--
-- ---------------------------------------------------------------------------
-- Why this migration does NOT delete anything
-- ---------------------------------------------------------------------------
-- An earlier draft of this migration deleted every salon row with city_id IS NULL, cascading
-- through appointments/bookings/booking_closure_reminders/reviews/client_reviews/invite_tokens,
-- on the theory that no production database exists for this project. That premise was never
-- verified and does not hold: this schema ships a `(default)` prod/Railway profile backed by
-- Neon (see CLAUDE.md — "Profiles & Env"), so a real database may exist and may hold real
-- bookings and reviews. An irreversible cascade DELETE has no place riding on an unverified
-- claim about deployment status.
--
-- Instead: this migration is a fail-loud pre-check. If any pre-existing salon row still has
-- city_id IS NULL, Flyway aborts here — loudly and reversibly (the app does not start, nothing
-- is destroyed) — instead of silently deleting user data. Any such row must be resolved by a
-- human (backfill its city_id, or otherwise dispose of it) before this migration can apply.
--
-- On every clean database — Testcontainers, CI, a freshly built local DB — city_id is NOT NULL
-- on every salon row from the moment Phase 10.6's write-path guard shipped, so the pre-check
-- always passes there and this migration is a plain (two-phase, see below) ALTER. See
-- scripts/dev-cleanup-null-city-salons.sql for the by-hand, local-only equivalent of the old
-- DELETE cascade, for a developer who has dirty rows in a disposable local DB and wants this
-- migration to apply without manually backfilling city_id.
--
-- ---------------------------------------------------------------------------
-- Fail-loud pre-check
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    dirty_count INTEGER;
BEGIN
    SELECT COUNT(*) INTO dirty_count FROM salons WHERE city_id IS NULL;

    IF dirty_count > 0 THEN
        RAISE EXCEPTION
            'V150 blocked: % salon row(s) still have city_id IS NULL. Promotion to NOT NULL '
            'cannot proceed until every such row is resolved by hand (backfill city_id, or '
            'otherwise dispose of the row) — this migration deliberately refuses to '
            'auto-delete user data. For a disposable LOCAL dev database only, see '
            'scripts/dev-cleanup-null-city-salons.sql.',
            dirty_count;
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- Two-phase NOT NULL promotion, phase 1 of 2 (Postgres 12+; this project runs
-- postgres:16-alpine both in docker/local/docker-compose.yml and in
-- AbstractIntegrationTest's Testcontainers pin, so the scan-skip V151 relies on is safe).
-- ---------------------------------------------------------------------------
-- A plain `ALTER COLUMN ... SET NOT NULL` takes an ACCESS EXCLUSIVE lock for the full duration
-- of the table scan it uses to validate the new constraint. salons is read on most authenticated
-- requests (owner/admin salon lookups), so holding that lock for a full-table scan is
-- unacceptable on a table with real traffic. The standard two-phase pattern avoids the scan
-- under the exclusive lock — but ONLY if the two phases land in separate transactions:
--   1. (HERE, V150) Add the CHECK constraint NOT VALID — near-instant, brief ACCESS EXCLUSIVE,
--      no table scan. Enforces the CHECK on every NEW insert/update from the moment this
--      statement commits (the pre-check above already proves every EXISTING row satisfies it,
--      so there is no enforcement gap of any kind — only a deferred retroactive scan).
--   2. (V151, separate version) VALIDATE the constraint (SHARE UPDATE EXCLUSIVE — concurrent
--      reads/writes proceed; does the scan), then SET NOT NULL (the planner satisfies it from
--      the validated CHECK without a second scan, Postgres 12+ — see V151 for detail).
--
-- Why VALIDATE CONSTRAINT is NOT run in this same file: Flyway runs an entire migration script
-- inside ONE transaction by default (`mixed=false`; no `spring.flyway.mixed` /
-- executeInTransaction=false anywhere in application.yml / application-local.yml /
-- application-prod.yml — verified). Postgres never downgrades a lock mid-transaction, so a
-- VALIDATE CONSTRAINT appended right here would still run its full-table scan while this very
-- transaction is still holding the ACCESS EXCLUSIVE lock the ADD CONSTRAINT below just took —
-- net lock severity/duration would be identical to a single validating
-- `ALTER COLUMN ... SET NOT NULL`, i.e. cosmetic, not real lock avoidance (this exact mistake
-- was already caught and fixed once in this repo — see V113's header comment and V115, which
-- did the same split for `bookings`'s CHECK constraints). The lighter SHARE UPDATE EXCLUSIVE
-- lock only materializes across a transaction boundary — a separate Flyway version — which is
-- why VALIDATE CONSTRAINT and SET NOT NULL are deferred to V151.
--
-- lock_timeout guards this ADD CONSTRAINT against queuing behind an unbounded lock wait,
-- mirroring the V128/V119/V103 pattern for ACCESS EXCLUSIVE DDL on hot tables.
SET LOCAL lock_timeout = '5s';

ALTER TABLE salons
    ADD CONSTRAINT chk_salons_city_id_not_null CHECK (city_id IS NOT NULL) NOT VALID;

-- VALIDATE CONSTRAINT chk_salons_city_id_not_null and ALTER COLUMN city_id SET NOT NULL both run
-- in V151 (separate transaction — see comment above).
