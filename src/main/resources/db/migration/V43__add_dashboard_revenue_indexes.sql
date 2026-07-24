-- Dashboard revenue query: INDEPENDENT_MASTER path
-- Filters on master_id + date range, status = COMPLETED only
CREATE INDEX IF NOT EXISTS idx_bookings_master_completed_starts_at
    ON bookings(master_id, starts_at)
    WHERE status = 'COMPLETED';

-- Dashboard revenue query: SALON_OWNER path
-- Filters on salon_id + date range, status = COMPLETED only
CREATE INDEX IF NOT EXISTS idx_bookings_salon_completed_starts_at
    ON bookings(salon_id, starts_at)
    WHERE status = 'COMPLETED';
