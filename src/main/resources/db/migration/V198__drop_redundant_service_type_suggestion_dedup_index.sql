-- V198: drop V76's non-unique idx_service_type_suggestion_dedup, superseded by V197's partial
-- UNIQUE ux_service_type_suggestion_pending_dedup on the identical (category_name,
-- lower(suggested_name)) WHERE status = 'PENDING' — a pure redundant write (§O-6).
--
-- Plain, TRANSACTIONAL drop (V123/V143 precedent), deliberately not CONCURRENTLY: an interrupted
-- DROP INDEX CONCURRENTLY leaves V198 recorded as FAILED in flyway_schema_history, which
-- FlywayRepairConfig does not repair, so every subsequent boot would crash-loop. A transactional
-- drop either commits or rolls back cleanly and is simply retried on the next boot.
--
-- The cost is ACCESS EXCLUSIVE on service_type_suggestion for the few milliseconds the catalog
-- update takes — the table is moderator-request-queue sized. Kept separate from V197 so that
-- migration never holds ACCESS EXCLUSIVE for its whole duplicate-collapse + index build.
-- lock_timeout bounds the WAIT for the lock (a long-open transaction fails the boot fast and the
-- deploy retries instead of queueing every reader behind us); statement_timeout bounds the work.
-- Both SET LOCAL — never leak onto the pooled connection (V159 header). IF EXISTS keeps the
-- migration replayable where an operator already dropped the index by hand.
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '1min';

DROP INDEX IF EXISTS idx_service_type_suggestion_dedup;
