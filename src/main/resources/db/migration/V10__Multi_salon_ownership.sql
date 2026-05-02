-- Remove the unique constraint on salons.owner_id so one owner can have many salons.
ALTER TABLE salons DROP CONSTRAINT IF EXISTS uq_salons_owner_id;

-- Add business_name to users (nullable; application enforces NOT NULL for SALON_OWNER).
ALTER TABLE users ADD COLUMN business_name VARCHAR(255);
