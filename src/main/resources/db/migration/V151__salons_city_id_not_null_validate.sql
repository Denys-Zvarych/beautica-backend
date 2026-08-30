-- V151 — the second half of the NOT VALID + VALIDATE CONSTRAINT split for
-- chk_salons_city_id_not_null (V150 added it NOT VALID against `salons`, a table read on most
-- authenticated requests).
--
-- Why this is its own migration/version, not appended to V150:
-- Flyway runs an entire migration script inside ONE transaction by default (`mixed=false`; no
-- `spring.flyway.mixed` / executeInTransaction=false configured anywhere in this repo — verified
-- across application.yml / application-local.yml / application-prod.yml). Postgres never
-- downgrades a lock mid-transaction. So a VALIDATE CONSTRAINT appended in the SAME file right
-- after its own ADD CONSTRAINT ... NOT VALID would still run its full-table scan while that same
-- transaction is still holding the ACCESS EXCLUSIVE lock the ADD CONSTRAINT took a moment
-- earlier — net lock severity/duration identical to a single validating
-- `ALTER COLUMN ... SET NOT NULL`. The lock-avoidance idiom only works when the two statements
-- land in SEPARATE transactions, i.e. separate Flyway versions: V150 commits (releasing its
-- ACCESS EXCLUSIVE lock) before this migration's VALIDATE CONSTRAINT statement begins, so the
-- scan here runs under the much lighter SHARE UPDATE EXCLUSIVE lock instead — `salons` stays
-- readable/writable by concurrent transactions for the duration of the scan. This mirrors the
-- V113/V115 precedent for `bookings`'s CHECK constraints exactly.
--
-- No enforcement gap: ADD CONSTRAINT ... NOT VALID already enforces the CHECK on every NEW
-- insert/update from the moment V150 committed (and V150's fail-loud pre-check already proved
-- every pre-existing row satisfies it too). This migration only makes that guarantee durable in
-- `pg_constraint.convalidated`, not change what is or isn't accepted going forward.
--
-- lock_timeout guards both statements below against queuing behind an unbounded lock wait,
-- mirroring the V128/V119/V103 pattern for DDL on hot tables.
SET LOCAL lock_timeout = '5s';

ALTER TABLE salons VALIDATE CONSTRAINT chk_salons_city_id_not_null;

-- SET NOT NULL takes ACCESS EXCLUSIVE, same as any ALTER COLUMN, but on Postgres 12+ the planner
-- can prove the new NOT NULL from the just-validated CHECK (the CHECK's condition,
-- `city_id IS NOT NULL`, is textually equivalent) without re-scanning the table, so this
-- statement's ACCESS EXCLUSIVE hold is catalog-only and effectively instant — this project runs
-- postgres:16-alpine both in docker/local/docker-compose.yml and in AbstractIntegrationTest's
-- Testcontainers pin, so the scan-skip is safe to rely on here.
ALTER TABLE salons ALTER COLUMN city_id SET NOT NULL;

-- The helper CHECK is left in place rather than dropped: Postgres does not automatically fold a
-- column's own NOT NULL back into a redundant CHECK, but the CHECK is also not redundant to drop
-- blindly — a future column-level tool (pg_dump --schema-only diffing, static-analysis of
-- constraints) benefits from the explicit, named, self-documenting constraint, and leaving it
-- costs nothing (no extra scan, negligible catalog/storage overhead). Dropping it would also
-- require re-adding a symmetrical NOT VALID/VALIDATE pair if this column's NOT NULL were ever
-- dropped and re-added later. Keep it.
