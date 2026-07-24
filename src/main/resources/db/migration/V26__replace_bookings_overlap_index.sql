-- Replace the 2-column partial index (master_id, ends_at) with a 3-column variant that
-- includes starts_at. The slot-overlap query filters on both ends_at > :start AND starts_at < :end,
-- so including starts_at allows the planner to satisfy both predicates from the index alone.
DROP INDEX IF EXISTS idx_bookings_master_active_ends_at;

CREATE INDEX idx_bookings_master_slot_overlap
    ON bookings(master_id, starts_at, ends_at)
    WHERE status IN ('PENDING', 'CONFIRMED');
