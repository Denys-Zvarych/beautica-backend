-- Phase 464 — one owner may hold several ACTIVE copies of one service type («копії послуги»).
--
-- Replaces V121's ux_service_def_owner_service_type_active (owner_type, owner_id, service_type_id)
-- with a key that also carries the base duration and the trimmed, case-folded name. Two active
-- copies of one type must now differ in base duration OR in name; the same type with the same
-- duration and the same name (compared lower(btrim(name))) is still a duplicate. Price never
-- participates (track D2 / phase 464 D3), and the per-master duration_override_minutes does not
-- either (D2) — this is the owner's catalogue rule.
--
-- No data step: the new key is strictly WEAKER than V121's (every row pair that differs here
-- already differed on service_type_id there, or V121 would have refused it), so every existing
-- active population already satisfies it.
--
-- The name expression lower(btrim(name)) is the single source of truth for name comparison:
-- ServiceRepository's duplicate pre-checks use the same expression, and ServiceCatalogService
-- translates a violation of THIS index name (DUPLICATE_SERVICE_INDEX) into the 409
-- DUPLICATE_SERVICE response. Renaming the index requires changing that constant in the same PR.
--
-- Forward-only (D4): rolling back to V121's key would need V121's dedup step again, because
-- copies created under this index collide under V121's. The down path is documented here, not
-- scripted: deactivate all but one active row per (owner_type, owner_id, service_type_id), then
-- recreate V121's index.

SET LOCAL lock_timeout = '5s';

-- Build the new index FIRST (SHARE lock: reads and other index builds proceed), then drop V121's.
-- The ACCESS EXCLUSIVE lock DROP INDEX takes is thus held only for the brief final statement.
-- Both are valid together: the new key is weaker, so every row satisfying V121's satisfies it.
CREATE UNIQUE INDEX ux_service_def_owner_type_duration_name_active
    ON service_definitions (owner_type, owner_id, service_type_id, base_duration_minutes, lower(btrim(name)))
    WHERE is_active = true;

DROP INDEX IF EXISTS ux_service_def_owner_service_type_active;
