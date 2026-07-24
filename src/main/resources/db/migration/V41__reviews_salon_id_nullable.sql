-- V40 made salon_id NOT NULL; INDEPENDENT_MASTER bookings have null salon_id (masters.salon_id nullable, bookings.salon_id nullable).
-- Drop NOT NULL, rebuild the salon index as partial to keep salon-scoped query performance.
-- salon_id FK retains ON DELETE NO ACTION (default): salon hard-delete is blocked when reviews exist.
-- Salons must be soft-deleted (isActive=false); GDPR erasure path uses anonymization, not hard-delete.
ALTER TABLE reviews ALTER COLUMN salon_id DROP NOT NULL;

DROP INDEX IF EXISTS idx_reviews_salon_created;
CREATE INDEX idx_reviews_salon_created ON reviews(salon_id, created_at DESC)
    WHERE salon_id IS NOT NULL;
CREATE INDEX idx_reviews_independent_master ON reviews(created_at DESC)
    WHERE salon_id IS NULL;

COMMENT ON COLUMN reviews.salon_id IS
    'NULL for INDEPENDENT_MASTER bookings (no salon affiliation). '
    'Salon-scoped queries MUST use WHERE salon_id = :id — never WHERE salon_id = :id OR salon_id IS NULL.';
