-- Multi-service single-visit booking (BE-1) — link `bookings` to its `appointments` aggregate.
--
-- Additive and nullable by design: every existing (legacy single-service) booking keeps
-- appointment_id = NULL and behaves exactly as before. A future multi-service visit (BE-3) sets
-- this FK on each of the N chained booking rows it creates under one appointment header. No
-- backfill, no NOT NULL, and the V18/V113 `no_overlapping_bookings` EXCLUDE constraint on
-- master_id is deliberately UNTOUCHED — overlap protection stays per-booking-row, which is what
-- keeps every existing query/index/DTO valid.
ALTER TABLE bookings ADD COLUMN appointment_id UUID REFERENCES appointments(id);

-- Partial index (§E-5 / §O-6): only multi-service bookings ever carry a non-NULL appointment_id,
-- so the "fetch all rows of this visit" lookup (BE-2/BE-3) indexes only those rows and skips the
-- overwhelmingly-NULL legacy single-service majority, avoiding write amplification on every
-- single-service insert.
CREATE INDEX idx_bookings_appointment ON bookings (appointment_id)
    WHERE appointment_id IS NOT NULL;
