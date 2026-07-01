-- Phase — SEARCH contract perf: index-serve the REVIEWS_DESC master sort.
--
-- SearchService.appendOrderBy emits, for SearchSort.REVIEWS_DESC:
--     ORDER BY m.review_count DESC NULLS LAST, m.id
-- under the constant predicate `m.is_active = true`. Without a matching index
-- this Top-N pagination degrades into a full district sort. This partial
-- composite mirrors the ORDER BY column order exactly (review_count DESC NULLS
-- LAST, id) so Postgres can drive an index-ordered Limit instead — the same
-- shape as idx_masters_active_rating (V36) does for RATING_DESC.
--
-- `id` is appended as the deterministic tie-breaker matching the ORDER BY.
-- Partial WHERE is_active = true keeps soft-deleted masters out of the B-tree
-- (cheaper writes, smaller working set) and lets the planner skip the
-- is_active recheck.
--
-- Additive + idempotent: IF NOT EXISTS makes a clean-DB replay and a re-run a
-- no-op. No data is written. (CONCURRENTLY is intentionally NOT used — Flyway
-- runs each migration in a transaction; a brief ACCESS EXCLUSIVE lock is
-- acceptable at current table sizes.)

CREATE INDEX IF NOT EXISTS idx_masters_active_review_count
    ON masters (review_count DESC NULLS LAST, id)
    WHERE is_active = true;
