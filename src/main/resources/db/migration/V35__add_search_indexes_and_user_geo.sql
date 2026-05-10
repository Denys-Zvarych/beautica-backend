-- Phase 6.1 — Search Indexes DB Schema
--
-- Adds the geo columns on users (city, region) that the architecture spec
-- (ARCHITECTURE-backend.md §0.5) requires but were never created by an
-- earlier migration, then creates the five search indexes that back the
-- master/salon discovery queries introduced in Phase 6.

-- Step 0 — Add users geo columns (required by idx_users_city_region below).
ALTER TABLE users ADD COLUMN IF NOT EXISTS city VARCHAR(100);
ALTER TABLE users ADD COLUMN IF NOT EXISTS region VARCHAR(100);

-- Step 1 — Search indexes.
-- (idx_service_def_category on service_definitions(category) already exists from V6 —
-- the Phase 6.1 doc's idx_service_definitions_category is intentionally NOT created
-- to avoid a duplicate index. See docs/backlog.md for the naming-consistency follow-up.)
CREATE INDEX IF NOT EXISTS idx_users_city_region ON users(city, region);
CREATE INDEX IF NOT EXISTS idx_salons_city_region ON salons(city, region);
CREATE INDEX IF NOT EXISTS idx_masters_avg_rating ON masters(avg_rating DESC);
CREATE INDEX IF NOT EXISTS idx_master_services_price_override ON master_services(price_override);
