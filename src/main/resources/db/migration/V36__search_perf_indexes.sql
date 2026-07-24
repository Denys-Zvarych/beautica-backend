-- Phase 6.5 — Search performance indexes.
--
-- Replaces three non-partial single-purpose indexes from V35 with partial /
-- covering variants that match the real predicates issued by the master and
-- salon search SQL in SearchService. Every active-record query filters by
-- `is_active = TRUE`, so a partial index keeps writes cheaper (only active
-- rows enter the B-tree) AND lets the planner skip the `is_active` recheck
-- when it has already proven the predicate via the index.
--
-- The non-partial siblings from V35 are dropped IN THE SAME MIGRATION so we
-- never carry two indexes that cover overlapping predicates (double writes,
-- wasted bloat).
--
-- Trade-offs:
--   * Partial-index writes are slightly more expensive than full-table writes
--     when the predicate evaluates to TRUE (Postgres still has to evaluate
--     the WHERE clause). The benefit is that deactivated rows do not enter
--     the index at all — fewer leaf splits, smaller working set in shared
--     buffers, and (most importantly) the planner can use the index
--     directly without a recheck on the heap.
--   * The `idx_masters_active_rating` index pairs `(avg_rating DESC NULLS LAST, id)`
--     so that the ORDER BY + LIMIT pagination in SearchService.MASTER_SEARCH_SQL
--     can use an index-only scan when no other WHERE predicates narrow the set.
--     `id` is included as the deterministic tie-breaker matching the ORDER BY.
--
-- NOTE: idx_salons_city_region is intentionally NOT partial — salons are
-- owner-controlled, low-churn, and the active/inactive ratio skews heavily
-- active. The write overhead of evaluating the predicate would not pay off.

-- ── masters — covering composite for rating-sorted pagination ────────────────
-- The non-partial idx_masters_avg_rating from V35 is superseded by the partial
-- composite below: the partial form filters at index level (so the planner
-- skips the heap recheck for `m.is_active = true`), and the `(rating, id)`
-- column order matches the ORDER BY in the master-search SQL exactly.
CREATE INDEX IF NOT EXISTS idx_masters_active_rating
    ON masters (avg_rating DESC NULLS LAST, id)
    WHERE is_active = true;

DROP INDEX IF EXISTS idx_masters_avg_rating;

-- ── users — geo lookup restricted to active users ────────────────────────────
-- The master-search SQL always joins `users u ON m.user_id = u.id` and filters
-- `u.is_active = true`. Making this partial avoids indexing the long tail of
-- soft-deleted / disabled accounts that will never appear in discovery.
CREATE INDEX IF NOT EXISTS idx_users_active_city_region
    ON users (city, region)
    WHERE is_active = true;

DROP INDEX IF EXISTS idx_users_city_region;

-- ── master_services — partial index for the active-row LEFT JOIN ─────────────
-- The LEFT JOIN in master-search is `ON ms.master_id = m.id AND ms.is_active = true`.
-- A partial index on `master_id WHERE is_active = true` lets the planner satisfy
-- both predicates from the index alone, avoiding a heap probe per row.
CREATE INDEX IF NOT EXISTS idx_master_services_master_active
    ON master_services (master_id)
    WHERE is_active = true;
