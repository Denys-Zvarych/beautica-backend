-- Bound lock acquisition, exactly as V157 does (see that file's header for the full argument).
-- DROP COLUMN takes ACCESS EXCLUSIVE on `users` — the table behind every login and every
-- JWT-backed read — so a long-running transaction holding a conflicting lock would park this ALTER
-- in the lock queue and stall the whole application behind it. With a 3s ceiling the migration
-- instead raises "canceling statement due to lock timeout", Flyway rolls the file back (it is
-- transactional) and the deploy fails loudly. The work itself is catalog-only and sub-second:
-- Postgres DROP COLUMN does not rewrite the table.
--
-- SET LOCAL, not SET: scoped to Flyway's per-migration transaction so neither value leaks onto the
-- pooled connection (under the `test` profile Flyway borrows the main Hikari pool, and Hikari
-- resets autoCommit/isolation/readOnly/catalog only, never GUCs). Same reasoning as V157.
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '1min';

-- =============================================================================
-- Phase 295 — drop users.scrubbed_at, the last remnant of the retired phase 291
-- PII-scrub apparatus.
--
-- docs/backend-phases/phase-295-salon-deletion-hard-deletes-staff.md § Decisions D2 / D5
--
-- WHY THE COLUMN IS GONE, NOT MERELY UNUSED.
-- V155 added it as the idempotency marker for phase 291's staff PII scrub: salon deletion
-- DEACTIVATED a staff `users` row and rewrote its email to a `deleted+<uuid>@beautica-deleted.invalid`
-- tombstone, and `scrubbed_at` recorded that this had happened so a second DELETE would not
-- re-scrub. The 2026-09-04 reversal (recorded in full in phase 294 § Decisions — CLOSED, R1/R2)
-- replaced that whole design: salon deletion now HARD-DELETES the staff `users` row. There is no
-- surviving row to tombstone, to scrub, or to mark — the email is gone because the row is gone.
--
-- Idempotency now comes from ROW ABSENCE, not from a flag (phase 295 D4): a second
-- DELETE /salons/{id} resolves an empty staff set and writes nothing. A column that only ever
-- meant "this surviving row was already scrubbed" has no meaning left to carry.
--
-- WHY THIS IS DESTRUCTIVE-BUT-SAFE. Every non-NULL value this column could hold was written by
-- the phase 291 cascade against a row that the phase 295 cascade would now have deleted outright.
-- Nothing reads it: `User#scrubbedAt`, `User#getScrubbedAt()` and `User#scrubPii(...)` — its only
-- mappers and its only writer — are deleted in the SAME commit as this migration (D2), which is
-- also why this is V158 and not an edit to V157: dropping the column while the entity still maps
-- it fails Hibernate's ddl-auto=validate at boot (D5). V157 is committed and shipped; its
-- checksum must never move.
--
-- NOT REVERSIBLE BY RE-ADDING THE COLUMN. `ALTER TABLE users ADD COLUMN scrubbed_at TIMESTAMPTZ`
-- restores the shape but not the data. That is intentional and harmless: the rows those timestamps
-- described do not exist any more either.
-- =============================================================================

ALTER TABLE users
    DROP COLUMN IF EXISTS scrubbed_at;
