-- idx_bookings_master_active_starts_at (created in V18) is superseded by
-- idx_bookings_master_slot_overlap (created in V26), which covers (master_id, starts_at, ends_at)
-- for the same WHERE status IN ('PENDING','CONFIRMED') filter.
-- The full index idx_bookings_master_starts_at is retained for the master-calendar query
-- which includes COMPLETED status.
DROP INDEX IF EXISTS idx_bookings_master_active_starts_at;
