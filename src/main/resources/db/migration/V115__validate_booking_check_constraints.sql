-- Track 24.x/25.x follow-up (MEDIUM finding, residual after re-audit) — the second half of the
-- NOT VALID + VALIDATE CONSTRAINT split for the three CHECK constraints V113 and V114 added
-- against `bookings` (the hottest write table in the system).
--
-- Why this is its own migration/version, not appended to V113 or V114:
-- Flyway runs an entire migration script inside ONE transaction by default (`mixed=false`; no
-- `spring.flyway.mixed` / executeInTransaction=false configured anywhere in this repo — verified
-- across application.yml / application-local.yml / application-prod.yml). Postgres never
-- downgrades a lock mid-transaction. So a VALIDATE CONSTRAINT appended in the SAME file right
-- after its own ADD CONSTRAINT ... NOT VALID would still run its full-table scan while that same
-- transaction is still holding the ACCESS EXCLUSIVE lock the ADD CONSTRAINT took a moment
-- earlier — net lock severity/duration identical to a single validating ADD CONSTRAINT. The
-- lock-avoidance idiom only works when the two statements land in SEPARATE transactions, i.e.
-- separate Flyway versions: V113/V114 each commit (releasing their ACCESS EXCLUSIVE lock) before
-- this migration's VALIDATE CONSTRAINT statements begin, so each scan here runs under the much
-- lighter SHARE UPDATE EXCLUSIVE lock instead — `bookings` stays readable/writable by concurrent
-- transactions for the duration of the scan.
--
-- No enforcement gap: ADD CONSTRAINT ... NOT VALID already enforces each CHECK on every NEW
-- insert/update from the moment V113/V114 committed. This migration only proves, retroactively,
-- that every PRE-EXISTING row also satisfies it — both V113 and V114 backfilled their target
-- columns before adding the corresponding CHECK, so this scan is expected to find zero
-- violations; it exists to make that guarantee durable in `pg_constraint.convalidated`, not to
-- change what is or isn't accepted going forward.
ALTER TABLE bookings VALIDATE CONSTRAINT chk_booking_status;
ALTER TABLE bookings VALIDATE CONSTRAINT chk_provider_comment_status;
ALTER TABLE bookings VALIDATE CONSTRAINT chk_client_cancellation_note_status;
