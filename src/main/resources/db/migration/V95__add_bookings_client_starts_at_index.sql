-- Phase 19.3 perf: BookingRepository.findClientBookingDetails(clientId, NULL, Pageable)
-- effective query is `WHERE client_id = ? ORDER BY starts_at DESC LIMIT ?`.
-- The V18 composite idx_bookings_client_status_starts_at (client_id, status, starts_at)
-- cannot serve that ORDER BY: `status` sits between the two needed columns, so Postgres
-- must sort the client's whole history before applying LIMIT (a Sort node).
-- This index (client_id, starts_at DESC) yields an index-ordered scan, no Sort node,
-- for the unfiltered list shape.
CREATE INDEX IF NOT EXISTS idx_bookings_client_starts_at
    ON bookings (client_id, starts_at DESC);

-- idx_bookings_client_id (client_id) was created in V18 and already dropped in V24
-- (strict prefix of idx_bookings_client_status_starts_at, and now of the index above).
-- Re-issued defensively as a no-op IF EXISTS so any environment that somehow still
-- carries the redundant single-column index sheds its write-path cost.
DROP INDEX IF EXISTS idx_bookings_client_id;
