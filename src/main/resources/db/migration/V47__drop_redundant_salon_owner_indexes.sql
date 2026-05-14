-- idx_salons_owner_active (V20) and idx_salons_owner_id (V3) are now fully covered
-- by idx_salons_owner_active_created (V46). Dropping to eliminate write amplification.
DROP INDEX IF EXISTS idx_salons_owner_active;
DROP INDEX IF EXISTS idx_salons_owner_id;
