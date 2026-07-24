-- V73: Re-parent service_types onto platform_categories taxonomy.
--
-- Context
-- -------
-- service_types.category_id is a UUID FK to the System-A service_categories
-- table (V12).  Platform category discovery (16.2+) needs each service_type to
-- resolve to a live platform_categories.name slug (e.g. 'MANICURE', 'HAIR').
-- This migration:
--   1. Seeds three new platform categories: HAIR, BODY, FACE.
--   2. Adds a new nullable column service_types.platform_category_name VARCHAR(100)
--      that mirrors the service_definitions.category slug convention — no numeric
--      FK; the service layer enforces integrity against platform_categories.name.
--   3. Populates platform_category_name for all 49 seeded service_type rows.
--
-- Design choice: slug column, not a new FK
-- -----------------------------------------
-- service_definitions.category is already a plain VARCHAR storing a platform-
-- category name slug with no DB FK (see V64 header).  This column follows the
-- same convention: platform_category_name VARCHAR(100) matches the CHECK
-- (name ~ '^[A-Z][A-Z0-9_]*$') shape and is validated at the service layer.
-- The old category_id FK is deprecated in place — NOT dropped — so existing
-- query paths and the CatalogCategory entity are unbroken for this release.
--
-- Idempotency
-- -----------
-- INSERT rows use ON CONFLICT (name) DO NOTHING so a re-apply of this migration
-- against an already-seeded DB is a no-op.
-- UPDATE rows use explicit WHERE conditions that are self-idempotent (re-setting
-- a column to the same value is a no-op in PostgreSQL row versioning).
--
-- Per-type mapping table (acceptance criterion — every slug → target category)
-- ---------------------------------------------------------------------------
-- MANICURE bucket (7 types):
--   manicure-classic         → MANICURE   (classic manicure)
--   manicure-hardware        → MANICURE   (hardware manicure)
--   gel-polish               → MANICURE   (gel-polish coverage, no pedicure prefix)
--   polish-removal           → MANICURE   (removal of coverage, nails default bucket)
--   nail-extension-acrylic   → MANICURE   (acrylic nail extension, generic nail)
--   nail-extension-gel       → MANICURE   (gel nail extension, generic nail)
--   nail-art                 → MANICURE   (nail art / design, generic nail)
--
-- PEDICURE bucket (2 types):
--   pedicure-classic         → PEDICURE   (pedicure-* prefix, LOCKED rule)
--   pedicure-hardware        → PEDICURE   (pedicure-* prefix, LOCKED rule)
--
-- EYELASH bucket (8 types):
--   lash-classic             → EYELASH
--   lash-2d                  → EYELASH
--   lash-3d                  → EYELASH
--   lash-volume              → EYELASH
--   lash-mega-volume         → EYELASH
--   lash-lamination          → EYELASH
--   lash-lift                → EYELASH
--   lash-removal             → EYELASH
--
-- BROWS bucket (6 types):
--   brow-shaping             → BROWS
--   brow-tinting             → BROWS
--   brow-lamination          → BROWS
--   brow-microblading        → BROWS
--   brow-powder-perm         → BROWS
--   brow-architecture        → BROWS
--
-- HAIRCUT bucket (3 types) — ASSUMPTION: cut/trim-intent types → HAIRCUT:
--   haircut-women            → HAIRCUT    (women's cut — primary intent is cutting)
--   haircut-men              → HAIRCUT    (men's cut — primary intent is cutting)
--   haircut-kids             → HAIRCUT    (kids' cut — primary intent is cutting)
--
-- HAIR bucket (6 types) — ASSUMPTION: coloring / styling / treatment → HAIR:
--   hair-coloring            → HAIR       (colour service, not a cut)
--   hair-highlights          → HAIR       (highlighting / mèche, not a cut)
--   hair-balayage            → HAIR       (balayage colour technique, not a cut)
--   hair-keratin             → HAIR       (keratin smoothing treatment)
--   hair-botox               → HAIR       (hair botox / deep conditioning treatment)
--   hair-styling             → HAIR       (blowout/updo/styling, not a cut)
--
-- FACE bucket (6 types):
--   facial-classic           → FACE       (classic facial cleanse)
--   facial-hardware          → FACE       (hardware/device facial)
--   facial-peel              → FACE       (chemical peel)
--   facial-meso              → FACE       (mesotherapy)
--   facial-massage           → FACE       (face massage)
--   facial-rf-lift           → FACE       (RF lifting)
--
-- BODY bucket (6 types):
--   wax-legs                 → BODY       (leg waxing)
--   wax-bikini               → BODY       (bikini waxing)
--   sugaring                 → BODY       (sugaring hair removal)
--   massage-general          → BODY       (full body massage)
--   massage-anticellulite    → BODY       (anti-cellulite massage)
--   body-wrap                → BODY       (body wrap)
--
-- MAKEUP bucket (5 types):
--   makeup-day               → MAKEUP
--   makeup-evening           → MAKEUP
--   makeup-bridal            → MAKEUP
--   perm-makeup-lips         → MAKEUP     (permanent lip makeup — makeup family)
--   perm-makeup-liner        → MAKEUP     (permanent eyeliner — makeup family)
--
-- Bucket totals: MANICURE=7, PEDICURE=2, EYELASH=8, BROWS=6,
--                HAIRCUT=3, HAIR=6, FACE=6, BODY=6, MAKEUP=5
-- Grand total: 49 service_types (matches V13 seed count exactly)

-- ============================================================
-- Step 1: seed the three new platform categories
-- ============================================================

INSERT INTO platform_categories (name, display_name, active, status)
VALUES
    ('HAIR', 'Волосся',  TRUE, 'APPROVED'),
    ('BODY', 'Тіло',     TRUE, 'APPROVED'),
    ('FACE', 'Обличчя',  TRUE, 'APPROVED')
ON CONFLICT (name) DO NOTHING;

-- ============================================================
-- Step 2: add the platform_category_name column to service_types
-- (idempotent: IF NOT EXISTS guard)
-- ============================================================

ALTER TABLE service_types
    ADD COLUMN IF NOT EXISTS platform_category_name VARCHAR(100)
        CONSTRAINT chk_service_types_platform_category_name
            CHECK (platform_category_name ~ '^[A-Z][A-Z0-9_]*$');

-- ============================================================
-- Step 3: populate platform_category_name for every seeded type
-- (49 rows — one UPDATE per bucket for reviewability)
-- ============================================================

-- MANICURE: manicure-* prefix + generic nail types (no pedicure prefix)
UPDATE service_types
SET    platform_category_name = 'MANICURE'
WHERE  slug IN (
    'manicure-classic',       -- Манікюр класичний
    'manicure-hardware',      -- Манікюр апаратний
    'gel-polish',             -- Покриття гель-лак
    'polish-removal',         -- Знімання покриття
    'nail-extension-acrylic', -- Нарощування нігтів (акрил)
    'nail-extension-gel',     -- Нарощування нігтів (гель)
    'nail-art'                -- Дизайн нігтів
);

-- PEDICURE: pedicure-* prefix (LOCKED rule)
UPDATE service_types
SET    platform_category_name = 'PEDICURE'
WHERE  slug IN (
    'pedicure-classic',       -- Педикюр класичний
    'pedicure-hardware'       -- Педикюр апаратний
);

-- EYELASH: all lash service types
UPDATE service_types
SET    platform_category_name = 'EYELASH'
WHERE  slug IN (
    'lash-classic',           -- Нарощування вій (класика)
    'lash-2d',                -- Нарощування вій 2D
    'lash-3d',                -- Нарощування вій 3D
    'lash-volume',            -- Нарощування вій обʼєм (4D-6D)
    'lash-mega-volume',       -- Нарощування вій мега-обʼєм (7D+)
    'lash-lamination',        -- Ламінування вій
    'lash-lift',              -- Підняття вій (ліфтинг)
    'lash-removal'            -- Видалення нарощених вій
);

-- BROWS: all brow service types
UPDATE service_types
SET    platform_category_name = 'BROWS'
WHERE  slug IN (
    'brow-shaping',           -- Оформлення брів
    'brow-tinting',           -- Фарбування брів
    'brow-lamination',        -- Ламінування брів
    'brow-microblading',      -- Мікроблейдинг
    'brow-powder-perm',       -- Пудрові брови (перманент)
    'brow-architecture'       -- Архітектура брів
);

-- HAIRCUT: cut/trim-intent types (ASSUMPTION — user review required)
UPDATE service_types
SET    platform_category_name = 'HAIRCUT'
WHERE  slug IN (
    'haircut-women',          -- Жіноча стрижка
    'haircut-men',            -- Чоловіча стрижка
    'haircut-kids'            -- Дитяча стрижка
);

-- HAIR: coloring / styling / treatment types (ASSUMPTION — user review required)
UPDATE service_types
SET    platform_category_name = 'HAIR'
WHERE  slug IN (
    'hair-coloring',          -- Фарбування волосся
    'hair-highlights',        -- Мелірування
    'hair-balayage',          -- Балаяж
    'hair-keratin',           -- Кератинове випрямлення
    'hair-botox',             -- Ботекс волосся
    'hair-styling'            -- Укладання
);

-- FACE: all face/skin service types
UPDATE service_types
SET    platform_category_name = 'FACE'
WHERE  slug IN (
    'facial-classic',         -- Класичне чищення обличчя
    'facial-hardware',        -- Апаратне чищення обличчя
    'facial-peel',            -- Хімічний пілінг
    'facial-meso',            -- Мезотерапія
    'facial-massage',         -- Масаж обличчя
    'facial-rf-lift'          -- RF-ліфтинг
);

-- BODY: all body service types
UPDATE service_types
SET    platform_category_name = 'BODY'
WHERE  slug IN (
    'wax-legs',               -- Депіляція воском (ноги)
    'wax-bikini',             -- Депіляція воском (бікіні)
    'sugaring',               -- Шугарінг
    'massage-general',        -- Масаж загальний
    'massage-anticellulite',  -- Антицелюлітний масаж
    'body-wrap'               -- Обгортання
);

-- MAKEUP: all makeup service types (including permanent makeup)
UPDATE service_types
SET    platform_category_name = 'MAKEUP'
WHERE  slug IN (
    'makeup-day',             -- Денний макіяж
    'makeup-evening',         -- Вечірній макіяж
    'makeup-bridal',          -- Весільний макіяж
    'perm-makeup-lips',       -- Перманентний макіяж (губи)
    'perm-makeup-liner'       -- Перманентний макіяж (стрілки)
);

-- ============================================================
-- Step 4: add FK constraint — closed-set guard
--
-- platform_categories.name carries uq_platform_categories_name UNIQUE (V64),
-- which makes it a valid FK target. The FK rejects any platform_category_name
-- value that is not present in the live platform_categories table, closing the
-- open set that the regex CHECK alone could not enforce.
-- FK added once by Flyway; no IF NOT EXISTS — unsupported by PostgreSQL for ADD CONSTRAINT.
-- ============================================================

ALTER TABLE service_types
    ADD CONSTRAINT fk_service_types_platform_category
        FOREIGN KEY (platform_category_name)
        REFERENCES platform_categories (name)
        ON UPDATE CASCADE
        ON DELETE RESTRICT;

-- ============================================================
-- Step 5: tighten NOT NULL now that all 49 rows are populated
--
-- All 49 UPDATE statements above have set platform_category_name on every row.
-- The FK constraint in step 4 would already reject NULLs for new inserts if we
-- omitted NOT NULL, but an explicit NOT NULL makes the schema self-documenting
-- and prevents any future omission from silently producing a NULL FK value.
-- Metadata-only on PG12+ (no table rewrite required).
-- Idempotent: SET NOT NULL is a no-op when the constraint already exists.
-- ============================================================

ALTER TABLE service_types
    ALTER COLUMN platform_category_name SET NOT NULL;

-- ============================================================
-- Step 6: add partial B-tree index for category-filtered queries
--
-- Phase 16.2+ endpoints filter service_types by platform_category_name with an
-- implicit WHERE is_active = TRUE (soft-delete pattern).  A partial index scoped
-- to active rows avoids indexing inactive/retired entries and keeps index writes
-- proportional to the live data set.
--
-- Index name must be exactly idx_service_types_platform_category (QA pinned).
-- Idempotent: CREATE INDEX IF NOT EXISTS.
-- ============================================================

CREATE INDEX IF NOT EXISTS idx_service_types_platform_category
    ON service_types (platform_category_name)
    WHERE is_active = TRUE;
