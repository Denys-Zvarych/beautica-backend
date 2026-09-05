-- V159 — the three unindexed reference columns the phase 295 staff hard-delete walks on every
-- DELETE /salons/{salonId}. Phase 295 perf audit findings HIGH-3 and MEDIUM-6.
--
-- WHY ONE FILE. All three indexes exist for the same statement — SalonService#deleteSalonStaff.
-- Splitting them would mean three files with three near-identical headers and no independent
-- rollback story.
--
-- ── 1. client_reviews.author_master_id (HIGH-3) ────────────────────────────────────────────────
-- Measured `Seq Scan on client_reviews` in TWO places, both per master of the salon being deleted:
--   a) the third EXISTS arm of MasterRepository#findIdsWithHistoricalReferences — the query that
--      decides whether a masters row can be DELETEd or must be DETACHed; and
--   b) the referential-integrity check Postgres itself runs on `DELETE FROM masters`, because
--      client_reviews_author_master_id_fkey (V128) is NO ACTION and Postgres does not index the
--      referencing side of a foreign key for you.
-- 11 rows today, so both are sub-millisecond — but two-sided ratings shipped (track 27.4-27.6) and
-- this table grows with every completed booking a provider reviews back. The sibling column
-- subject_client_id has had idx_client_reviews_subject_client since V128; author_master_id was
-- simply missed.
--
-- ── 2 + 3. the two ON DELETE SET NULL columns off `users` (MEDIUM-6) ──────────────────────────
-- platform_categories.requested_by_user_id and service_type_suggestion.requested_by_user_id are
-- both `REFERENCES users(id) ON DELETE SET NULL` with no index. A SET NULL action fires an RI
-- trigger per DELETEd row, so the staff cascade pays two Seq Scans x N staff accounts. Both tables
-- are tiny today (this is measured-trivial, not measured-slow) — the point is that the cost is
-- proportional to N and the fix is free; a moderator-request backlog of any size makes it real.
--
-- ── PLAIN CREATE INDEX, NOT CONCURRENTLY — measured, not assumed ───────────────────────────────
-- The phase 295 perf audit prescribed `CREATE INDEX CONCURRENTLY` in a
-- `-- flyway:executeInTransaction=false` file. That WAS BUILT AND REJECTED on evidence, twice:
--
--   1. It does not run. With `executeInTransaction=false` the CONCURRENTLY build fails with
--      `ERROR: canceling statement due to lock timeout`, because V83:18 sets `lock_timeout = '3s'`
--      WITHOUT `LOCAL` inside its own transaction — and a bare SET survives COMMIT and stays on
--      the session (verified directly: `BEGIN; SET lock_timeout='3s'; COMMIT; SHOW lock_timeout;`
--      -> `3s`). Under the `test` profile Flyway borrows the main Hikari pool
--      (FlywayDataSourceConfig is @Profile("!test")) and Hikari resets autoCommit/isolation/
--      readOnly/catalog only, never GUCs, so every migration after V83 — this one included — runs
--      under a 3 s lock ceiling. This is the exact hazard V157's own header documents.
--   2. It cannot be worked around inside the file. Flyway refuses a migration that MIXES
--      transactional and non-transactional statements, so an `executeInTransaction=false` file
--      admits neither a `SET`/`RESET lock_timeout` to raise that ceiling nor a `COMMENT ON INDEX`
--      ("Detected both transactional and non-transactional statements within the same migration").
--      Raising the ceiling from a preceding migration would then leak `lock_timeout = 0` (wait
--      forever) onto the pooled connection for the rest of the run — trading this problem for the
--      one V157's header warns broke a contended pg_advisory_xact_lock booking test.
--
-- And there is NO CONCURRENTLY precedent to appeal to: across V1-V158 not one migration executes
-- it. Every occurrence of the word is a COMMENT explaining why that migration did NOT use it —
-- V145 and V154 both argue the case explicitly, V154 for `users`, a far hotter and larger table
-- than any of the three below. `grep -rn CONCURRENTLY db/migration/` looks like ten precedents and
-- is ten refusals; read the matched LINE, not the filename.
--
-- The cost of the plain form here is a few milliseconds of ACCESS EXCLUSIVE on three small tables
-- (client_reviews: 11 rows; the two request tables: moderator-backlog scale). lock_timeout below
-- bounds the WAIT for that lock so a long-open transaction cannot head-of-line-block the deploy;
-- statement_timeout bounds the work while it is held (V120:42, V137:23, V138:45, V141:57, V157:20
-- — lock_timeout never covers execution, and neither implies the other). Both are SET LOCAL, so
-- neither leaks past this migration's transaction. Do NOT copy V83's bare SET.
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '1min';

CREATE INDEX idx_client_reviews_author_master
    ON client_reviews (author_master_id);

CREATE INDEX idx_platform_categories_requested_by
    ON platform_categories (requested_by_user_id);

CREATE INDEX idx_service_type_suggestion_requested_by
    ON service_type_suggestion (requested_by_user_id);

COMMENT ON INDEX idx_client_reviews_author_master IS
    'Phase 295 audit HIGH-3. Backs MasterRepository#findIdsWithHistoricalReferences'' third EXISTS '
    'arm AND the RI check on DELETE FROM masters (client_reviews_author_master_id_fkey is NO '
    'ACTION). Both Seq Scanned this table once per master before it existed.';

COMMENT ON INDEX idx_platform_categories_requested_by IS
    'Phase 295 audit MEDIUM-6. Backs the ON DELETE SET NULL RI trigger fired once per staff '
    'account by the salon-deletion hard delete.';

COMMENT ON INDEX idx_service_type_suggestion_requested_by IS
    'Phase 295 audit MEDIUM-6. Backs the ON DELETE SET NULL RI trigger fired once per staff '
    'account by the salon-deletion hard delete.';
