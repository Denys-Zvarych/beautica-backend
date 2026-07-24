-- Phase 19.4 perf: ReviewRepository.findMyReviews(clientId, Pageable)
-- effective query is `WHERE client_id = ? ORDER BY created_at DESC LIMIT ?`.
-- V40 only added the single-column idx_reviews_client_id (client_id), which serves the
-- equality but not the sort, so Postgres must sort the matched rows (a Sort node) before
-- applying LIMIT. This composite (client_id, created_at DESC) yields an index-ordered scan,
-- no Sort node, matching the public master/salon review paths (idx_reviews_master_created).
CREATE INDEX IF NOT EXISTS idx_reviews_client_created
    ON reviews (client_id, created_at DESC);

-- idx_reviews_client_id (client_id) is a strict left-prefix of the composite above, so the
-- new index covers every lookup the old one did. Dropping it loses no coverage and sheds the
-- redundant write-path cost (P2 redundant-index rule).
DROP INDEX IF EXISTS idx_reviews_client_id;
