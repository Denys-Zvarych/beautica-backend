-- HIGH-1 (Phase 19.1 favorites): composite index for the master-favorites LATERAL subquery.
--
-- FavoriteRepository.findFavoriteMasterRows correlates the per-client "latest booking
-- service name" lateral on (b.master_id = ?, b.client_id = ?) ORDER BY b.starts_at DESC
-- LIMIT 1. The pre-existing idx_bookings_master_starts_at (master_id, starts_at) leaves
-- client_id as a heap filter, forcing a wide index range scan per favorited master.
--
-- This composite (master_id, client_id, starts_at DESC) lets Postgres seek straight to the
-- (master, client) group and read the first row in starts_at-descending order — an index-only
-- top-1 lookup per favorited master instead of a filtered scan.
CREATE INDEX IF NOT EXISTS idx_bookings_master_client_starts_at
    ON bookings (master_id, client_id, starts_at DESC);
