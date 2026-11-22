-- V265 — B504 (catalog admin portal): make idx_service_types_platform_category a FULL index.
--
-- WHY.
-- V73 created idx_service_types_platform_category as a PARTIAL btree on
-- service_types(platform_category_name) WHERE is_active = TRUE. B504's catalog portal reads every type of a
-- category, hidden ones included (CatalogEntryQueryRepository: the per-category type_count sub-select and the
-- GET /internal/catalog/categories/{name}/service-types list). Their predicate has no is_active = TRUE, so the
-- partial index cannot serve them and both fall back to a sequential scan.
--
-- WHY A FULL INDEX COVERS EVERY EXISTING READER.
-- Every query that used the partial index filters on equality platform_category_name = :name, plus
-- is_active = TRUE as a residual filter the full index serves just as well:
--   ServiceTypeRepository.findActiveByPlatformCategoryName                       (types-by-category picker)
--   ServiceTypeRepository.findFirstActiveByPlatformCategoryNameAndNameUkIgnoreCase (promotion dedup)
--   ServiceTypeRepository.existsOtherInCategoryByNameUkIgnoreCase                  (B504 rename / unhide check)
-- The table is reference data (a few hundred rows, of which few are hidden), so indexing hidden rows too adds
-- almost nothing to index size or write cost.
--
-- SAME NAME, ON PURPOSE.
-- ServiceType's @Table(indexes) declares idx_service_types_platform_category on platform_category_name, and
-- ReparentServiceTypesMigrationTest pins that name, so the full index keeps it. That needs DROP then CREATE in
-- this one migration. V73 is NOT edited: it is applied everywhere, and changing it would change its checksum.
--
-- WHY PLAIN DROP / CREATE AND NOT CONCURRENTLY.
-- Same reasoning as V152: this project runs every migration inside Flyway's transaction (there is no
-- executeInTransaction=false sidecar anywhere). The table is tiny, so the ACCESS EXCLUSIVE / SHARE locks are held
-- for milliseconds. Inside one transaction the swap is atomic: no reader ever sees the table without the index,
-- and a failure rolls both statements back.
--
-- IF EXISTS / IF NOT EXISTS keep a replay idempotent (e.g. on a dump restored after a manual drop).

DROP INDEX IF EXISTS idx_service_types_platform_category;

CREATE INDEX IF NOT EXISTS idx_service_types_platform_category
    ON service_types (platform_category_name);
