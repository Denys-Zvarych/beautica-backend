-- Phase 357 audit-fix: the «toRateClient» leg of the pending-actions count
-- (BookingClosureRule#awaitingProviderClientReview) is `status = 'COMPLETED' AND client_id IS NOT NULL
-- AND NOT EXISTS (client_reviews ...)`, unbounded over a provider's whole COMPLETED history (decision 4:
-- no time window). V43's idx_bookings_master_completed_starts_at is keyed on (master_id, starts_at)
-- and does not carry client_id (V43's salon sibling was dropped in V123), so each COMPLETED row costs
-- a heap fetch just to test client_id. These two partial indexes mirror the exact predicate, one per
-- scope (master: /bookings/me/pending-actions/count; salon: /bookings/salon/{id}/pending-actions/count),
-- and stay small because they hold only completed, non-guest rows. The key is a single column because
-- the count needs no ordering; INCLUDE (id) makes the scan index-only for the
-- `NOT EXISTS (client_reviews WHERE booking_id = b.id)` anti-join probe on uq_client_reviews_booking.
--
-- Plain CREATE INDEX (not CONCURRENTLY): CONCURRENTLY cannot run inside Flyway's migration
-- transaction (same decision as V141). It takes SHARE on `bookings`, blocking writes (not reads) for
-- the build, so bound it: fail fast and let Flyway roll back cleanly (retry next deploy). SET LOCAL
-- scopes both timeouts to this migration's transaction. statement_timeout applies PER statement
-- (re-armed for each); two index builds follow, so the ceiling is 1min each.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '1min';

CREATE INDEX IF NOT EXISTS idx_bookings_master_completed_client
    ON bookings (master_id) INCLUDE (id)
    WHERE status = 'COMPLETED' AND client_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_bookings_salon_completed_client
    ON bookings (salon_id) INCLUDE (id)
    WHERE status = 'COMPLETED' AND client_id IS NOT NULL;
