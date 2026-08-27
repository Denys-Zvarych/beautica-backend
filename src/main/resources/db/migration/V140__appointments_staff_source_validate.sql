-- V140 — validate the three constraints V139 added NOT VALID (Phase 22.8).
--
-- Separated from V139 because Postgres will not downgrade a lock mid-transaction: keeping the
-- VALIDATE in V139 would run these scans under the ACCESS EXCLUSIVE that migration already holds,
-- which is exactly what the NOT VALID split exists to avoid. VALIDATE CONSTRAINT takes only SHARE
-- UPDATE EXCLUSIVE, which blocks neither readers nor writers (it does block autovacuum and other
-- SHARE UPDATE EXCLUSIVE holders, which is why the three are NOT merged — see below).
--
-- These scans cannot fail. Every pre-V139 row is APP or LINK and satisfies the carried-over branches
-- unchanged, and created_by_user_id is NULL on every pre-V139 row so the FK has nothing to check.
-- The VALIDATE is therefore a formality that upgrades the constraints to "trusted" so the planner
-- may use them for constraint exclusion — it is not a data-repair step.
--
-- Immutable-migration rule (Anti-Bug §O-9): fix-forward, max existing = V139.

-- Same lock guard and the same 5s value as V139. statement_timeout is larger here because these
-- ARE the size-dependent scans V139 deferred — but they run under a non-blocking lock, so a longer
-- ceiling costs concurrency nothing.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '5min';

-- THREE separate ALTER TABLE statements, deliberately NOT merged. V137's merge argument was about
-- minimising ACCESS EXCLUSIVE ACQUISITIONS; it does not transfer. Merging here would hold ONE SHARE
-- UPDATE EXCLUSIVE across all three scans — a longer window during which autovacuum cannot touch
-- the table — for no saving, since each subcommand still performs its own scan. Shorter individual
-- holds are strictly better under a lock that does not block DML.
ALTER TABLE appointments VALIDATE CONSTRAINT chk_appointment_source;
ALTER TABLE appointments VALIDATE CONSTRAINT chk_appointment_guest_fields;
ALTER TABLE appointments VALIDATE CONSTRAINT fk_appointments_created_by;
