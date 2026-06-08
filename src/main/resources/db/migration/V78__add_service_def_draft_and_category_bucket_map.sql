-- V78 (Phase 16.9.4): two schema capabilities the runtime promotion writer (16.9.1)
-- needs, neither of which exists today.
--
-- Part 1 — draft / needs-completion marker on service_definitions.
--   Approving a service-type suggestion now materializes a real, master-owned service
--   so the requested NAME displays for that master (Phase 16.9.0, Option B). But a
--   ServiceDefinition has NOT-NULL base_price / price_type / base_duration_minutes that
--   were never collected at request time. We persist the draft with SENTINEL placeholders
--   (base_price = 0, price_type = 'FIXED', base_duration_minutes = 0) and gate it out of
--   search + booking via is_active = FALSE AND is_draft = TRUE. This is the
--   "smallest blast radius" shape from the phase doc: no nullability change, the existing
--   chk_service_def_price_mode CHECK still holds (0 is a valid FIXED price; the row is just
--   never bookable while inactive).
--
-- Part 2 — data-driven System-B (platform_categories.name) -> System-A
--   (service_categories.id) bucket mapping. Creating a service_types row requires a
--   NOT-NULL category_id FK to service_categories, but no column links the System-B slug
--   to a System-A bucket — V75 hardcoded it per-INSERT. The promotion writer cannot
--   hardcode 8 UUIDs in Java; it resolves the bucket through this new column.
--
-- Idempotency / safety
--   * ADD COLUMN IF NOT EXISTS so a clean-DB replay / re-apply is a no-op.
--   * Backfill UPDATEs are guarded WHERE service_category_id IS NULL so a re-run touches
--     no already-mapped rows (self-idempotent, matching V73/V74 convention).
--   * Bucket UUIDs copied verbatim from V75__reseed_service_types_taxonomy.sql:36-43.
--   * No locality data — occupied-territory ban N/A.
--   * V78 is the next free version (latest applied = V77). Applied migrations are
--     immutable; this is a forward-only additive migration.

-- ============================================================
-- Part 1: draft marker on service_definitions.
-- DEFAULT FALSE so every existing row is non-draft; existing create paths set it
-- implicitly false (no @DynamicInsert needed).
-- ============================================================
ALTER TABLE service_definitions
    ADD COLUMN IF NOT EXISTS is_draft BOOLEAN NOT NULL DEFAULT FALSE;

-- ============================================================
-- Part 2: System-B -> System-A bucket mapping column on platform_categories.
-- Nullable: a brand-new self-service category (created via a category request) has no
-- V75 bucket and stays NULL; the promotion writer treats NULL as "cannot create a
-- ServiceType" and falls back to a draft ServiceDefinition with service_type = NULL.
-- ============================================================
ALTER TABLE platform_categories
    ADD COLUMN IF NOT EXISTS service_category_id UUID;

ALTER TABLE platform_categories
    DROP CONSTRAINT IF EXISTS fk_platform_categories_service_category;
ALTER TABLE platform_categories
    ADD CONSTRAINT fk_platform_categories_service_category
        FOREIGN KEY (service_category_id)
        REFERENCES service_categories (id)
        ON DELETE RESTRICT;

-- ------------------------------------------------------------
-- Backfill the bucket for every seeded platform category, using the EXACT coarse-bucket
-- assignments V75 used (V75:36-43 + per-INSERT comment blocks). Self-idempotent: only
-- rows still NULL are touched, so a re-apply is a no-op.
--
--   Nails       11111111-0001-0000-0000-000000000000
--   Eyelashes   11111111-0002-0000-0000-000000000000
--   Brows       11111111-0003-0000-0000-000000000000
--   Hair        11111111-0004-0000-0000-000000000000
--   Face / Skin 11111111-0005-0000-0000-000000000000
--   Body        11111111-0006-0000-0000-000000000000
--   Makeup      11111111-0007-0000-0000-000000000000
-- ------------------------------------------------------------

-- Hair (0004): HAIRDRESSING, HAIR_COLORING, HAIR_TREATMENT, HAIR_EXTENSIONS,
--              TRICHOLOGY, BARBERING, BEARD_CARE, SHAVING.
UPDATE platform_categories
SET    service_category_id = '11111111-0004-0000-0000-000000000000'
WHERE  service_category_id IS NULL
  AND  name IN ('HAIRDRESSING', 'HAIR_COLORING', 'HAIR_TREATMENT', 'HAIR_EXTENSIONS',
                'TRICHOLOGY', 'BARBERING', 'BEARD_CARE', 'SHAVING');

-- Nails (0001): NAIL_SERVICE, PODOLOGY.
UPDATE platform_categories
SET    service_category_id = '11111111-0001-0000-0000-000000000000'
WHERE  service_category_id IS NULL
  AND  name IN ('NAIL_SERVICE', 'PODOLOGY');

-- Brows (0003): BROWS.
UPDATE platform_categories
SET    service_category_id = '11111111-0003-0000-0000-000000000000'
WHERE  service_category_id IS NULL
  AND  name = 'BROWS';

-- Eyelashes (0002): LASH_LAMINATION, LASH_EXTENSIONS.
UPDATE platform_categories
SET    service_category_id = '11111111-0002-0000-0000-000000000000'
WHERE  service_category_id IS NULL
  AND  name IN ('LASH_LAMINATION', 'LASH_EXTENSIONS');

-- Makeup (0007): MAKEUP, PERMANENT_MAKEUP.
UPDATE platform_categories
SET    service_category_id = '11111111-0007-0000-0000-000000000000'
WHERE  service_category_id IS NULL
  AND  name IN ('MAKEUP', 'PERMANENT_MAKEUP');

-- Face / Skin (0005): COSMETOLOGY, HARDWARE_COSMETOLOGY, INJECTION_COSMETOLOGY,
--                     AESTHETIC_COSMETOLOGY, LASER_COSMETOLOGY.
UPDATE platform_categories
SET    service_category_id = '11111111-0005-0000-0000-000000000000'
WHERE  service_category_id IS NULL
  AND  name IN ('COSMETOLOGY', 'HARDWARE_COSMETOLOGY', 'INJECTION_COSMETOLOGY',
                'AESTHETIC_COSMETOLOGY', 'LASER_COSMETOLOGY');

-- Body (0006): HAIR_REMOVAL.
UPDATE platform_categories
SET    service_category_id = '11111111-0006-0000-0000-000000000000'
WHERE  service_category_id IS NULL
  AND  name = 'HAIR_REMOVAL';
