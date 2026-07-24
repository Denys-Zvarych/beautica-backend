-- PERF-M2: pre-computed minimum effective price on the masters table.
-- Eliminates the per-request MIN(COALESCE(ms.price_override, sd.base_price))
-- aggregate join in the master discovery search query.
--
-- Maintenance contract: ServiceCatalogService calls
-- MasterRepository.refreshMinEffectivePrice(masterId) after any change to
-- master_services.is_active or service_definitions.is_active for a given master.
-- NULL means the master has no active services — semantically correct, and such
-- masters are excluded naturally by the BETWEEN price filter in the search query.

ALTER TABLE masters
    ADD COLUMN IF NOT EXISTS min_effective_price NUMERIC(10, 2);

-- Backfill: compute from existing master_services + service_definitions.
-- A master with no active services gets NULL (correct — no bookable price).
UPDATE masters m
SET min_effective_price = (
    SELECT MIN(COALESCE(ms.price_override, sd.base_price))
    FROM master_services ms
    JOIN service_definitions sd ON ms.service_def_id = sd.id
    WHERE ms.master_id = m.id
      AND ms.is_active  = true
      AND sd.is_active  = true
);

-- Partial index: only covers rows where a price is present.
-- Range predicates (>= / <=) exclude NULL naturally via SQL three-valued logic;
-- the partial index covers exactly the rows that can satisfy a price filter.
CREATE INDEX IF NOT EXISTS idx_masters_min_effective_price
    ON masters (min_effective_price)
    WHERE min_effective_price IS NOT NULL;
