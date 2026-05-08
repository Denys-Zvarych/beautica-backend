CREATE INDEX IF NOT EXISTS idx_bookings_salon_status_starts_at
    ON bookings (salon_id, status, starts_at DESC)
    WHERE status IN ('PENDING', 'CONFIRMED', 'COMPLETED');
