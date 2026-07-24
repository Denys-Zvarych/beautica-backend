-- Symmetric twin of V104's salon rating indexes, for the Phase 8.11 master rating sorts.
-- The only existing master-scoped index is idx_reviews_master_created(master_id,
-- created_at DESC), which serves only the NEWEST/OLDEST sorts. It does not serve the
-- HIGHEST/LOWEST sorts (findIdsByMasterIdOrderByRatingDescCreatedAtDesc /
-- ...RatingAscCreatedAtDesc), which order by (master_id, rating {DESC,ASC}, created_at DESC).
--
-- TWO indexes are added, not one: a single (rating DESC, created_at DESC) index
-- backward-scanned yields (rating ASC, created_at ASC) — that does NOT match the
-- LOWEST sort's tiebreak of created_at DESC. Each sort's exact (direction, tiebreak)
-- pair needs its own index to avoid a heap-level re-sort.
--
-- PLAIN (not partial): reviews.master_id is NOT NULL, so no WHERE predicate (unlike the
-- salon partial indexes in V104, which guard salon_id IS NOT NULL).
CREATE INDEX idx_reviews_master_rating_desc ON reviews(master_id, rating DESC, created_at DESC);
CREATE INDEX idx_reviews_master_rating_asc  ON reviews(master_id, rating ASC,  created_at DESC);
