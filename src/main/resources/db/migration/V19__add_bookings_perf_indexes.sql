-- Composite index to cover SALON_OWNER list query sorted by starts_at
CREATE INDEX IF NOT EXISTS idx_bookings_salon_starts_at
    ON bookings(salon_id, starts_at DESC);

-- Partial index to cover ends_at predicate in overlap check
CREATE INDEX IF NOT EXISTS idx_bookings_master_active_ends_at
    ON bookings(master_id, ends_at)
    WHERE status IN ('PENDING', 'CONFIRMED');
