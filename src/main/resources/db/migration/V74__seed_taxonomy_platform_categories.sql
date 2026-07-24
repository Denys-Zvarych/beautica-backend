-- V74: Seed the 21-category beauty-service taxonomy into platform_categories.
--
-- Context (track 17.x)
-- --------------------
-- platform_categories (V64/V65/V66) is the authoritative picker read by
-- GET /api/v1/service-categories/approved. The approved taxonomy replaces the
-- legacy 9-category set with 21 categories. This migration:
--   1. Renames the three legacy slugs that have a clean 1:1 successor
--      (ON UPDATE CASCADE on the V73 FK propagates the rename to
--       service_types.platform_category_name automatically — those rows are
--       being fully re-seeded in V75 anyway).
--   2. Refreshes display_name / status / active on the two kept slugs.
--   3. Soft-disables (active = FALSE) every superseded legacy slug that has no
--      1:1 successor, instead of DELETE: the row is referenced by the
--      UNIQUE(name) workflow guard and may be a recorded category on legacy
--      service_definitions rows. Soft-disable drops it from the picker and the
--      validate-category gate (both intended) without breaking FKs.
--   4. Inserts the remaining new categories as APPROVED, active = TRUE rows.
--
-- Slug rename map (legacy -> new), all carry ON UPDATE CASCADE to service_types:
--   HAIRCUT  -> HAIRDRESSING     (main hair-styling category, "Перукарські послуги")
--   MANICURE -> NAIL_SERVICE     ("Нігтьовий сервіс")
--   EYELASH  -> LASH_EXTENSIONS  ("Нарощення вій")
--
-- Kept as-is (display_name refreshed): BROWS, MAKEUP.
--
-- Soft-disabled leftovers (no 1:1 successor): PEDICURE, HAIR, BODY, FACE, OTHER.
--   OTHER was already active = FALSE (V66); the UPDATE below is a self-idempotent
--   no-op for it.
--
-- Idempotency
-- -----------
-- Renames use WHERE name = '<legacy>' so a second apply (legacy row already
-- gone) matches zero rows -> no-op. Inserts use ON CONFLICT (name) DO UPDATE so a
-- re-apply re-asserts the intended display_name/status/active. Soft-disable
-- UPDATEs are self-idempotent (setting active to the same value is a no-op).
--
-- name regex: ^[A-Z][A-Z0-9_]*$ (chk_platform_categories_name) — every slug below
-- conforms. status is constrained to PENDING/APPROVED/REJECTED.

-- ============================================================
-- Step 1: rename the three legacy slugs with a clean 1:1 successor.
-- ON UPDATE CASCADE (fk_service_types_platform_category, V73) propagates the new
-- name to any service_types row; V75 re-seeds service_types regardless.
-- Guarded so it is a no-op if the legacy row is already renamed (re-apply) or if
-- the successor already exists (collision-safe: rename only when the source row
-- is still present AND the target name is free).
-- ============================================================

UPDATE platform_categories
SET    name         = 'HAIRDRESSING',
       display_name = 'Перукарські послуги',
       status       = 'APPROVED',
       active       = TRUE
WHERE  name = 'HAIRCUT'
  AND  NOT EXISTS (SELECT 1 FROM platform_categories p2 WHERE p2.name = 'HAIRDRESSING');

UPDATE platform_categories
SET    name         = 'NAIL_SERVICE',
       display_name = 'Нігтьовий сервіс',
       status       = 'APPROVED',
       active       = TRUE
WHERE  name = 'MANICURE'
  AND  NOT EXISTS (SELECT 1 FROM platform_categories p2 WHERE p2.name = 'NAIL_SERVICE');

UPDATE platform_categories
SET    name         = 'LASH_EXTENSIONS',
       display_name = 'Нарощення вій',
       status       = 'APPROVED',
       active       = TRUE
WHERE  name = 'EYELASH'
  AND  NOT EXISTS (SELECT 1 FROM platform_categories p2 WHERE p2.name = 'LASH_EXTENSIONS');

-- ============================================================
-- Step 2: refresh the two kept slugs (display_name aligned with the new
-- taxonomy labels; re-assert APPROVED/active).
-- ============================================================

UPDATE platform_categories
SET    display_name = 'Оформлення брів',
       status       = 'APPROVED',
       active       = TRUE
WHERE  name = 'BROWS';

UPDATE platform_categories
SET    display_name = 'Макіяж',
       status       = 'APPROVED',
       active       = TRUE
WHERE  name = 'MAKEUP';

-- ============================================================
-- Step 3: soft-disable superseded legacy slugs with no 1:1 successor.
-- Kept (not DELETEd) for UNIQUE(name)-guard + legacy service_definitions refs.
-- ============================================================

UPDATE platform_categories
SET    active = FALSE
WHERE  name IN ('PEDICURE', 'HAIR', 'BODY', 'FACE', 'OTHER');

-- ============================================================
-- Step 4: insert every remaining new category (APPROVED, active = TRUE).
-- The three renamed slugs (HAIRDRESSING, NAIL_SERVICE, LASH_EXTENSIONS) and the
-- two kept slugs (BROWS, MAKEUP) are intentionally NOT repeated here.
-- ON CONFLICT (name) DO UPDATE re-asserts the intended state on re-apply and also
-- covers the case where a successor already existed before this migration ran.
-- ============================================================

INSERT INTO platform_categories (name, display_name, status, active) VALUES
    ('HAIR_COLORING',          'Фарбування волосся',     'APPROVED', TRUE),
    ('HAIR_TREATMENT',         'Відновлення волосся',    'APPROVED', TRUE),
    ('HAIR_EXTENSIONS',        'Нарощування волосся',    'APPROVED', TRUE),
    ('TRICHOLOGY',             'Трихологія',             'APPROVED', TRUE),
    ('PODOLOGY',               'Подологія',              'APPROVED', TRUE),
    ('LASH_LAMINATION',        'Ламінування вій',        'APPROVED', TRUE),
    ('COSMETOLOGY',            'Косметологія',           'APPROVED', TRUE),
    ('HARDWARE_COSMETOLOGY',   'Апаратна косметологія',  'APPROVED', TRUE),
    ('INJECTION_COSMETOLOGY',  'Ін''єкційна косметологія','APPROVED', TRUE),
    ('AESTHETIC_COSMETOLOGY',  'Естетична косметологія', 'APPROVED', TRUE),
    ('LASER_COSMETOLOGY',      'Лазерна косметологія',   'APPROVED', TRUE),
    ('HAIR_REMOVAL',           'Депіляція та епіляція',  'APPROVED', TRUE),
    ('PERMANENT_MAKEUP',       'Перманентний макіяж',    'APPROVED', TRUE),
    ('BARBERING',              'Барберінг',              'APPROVED', TRUE),
    ('BEARD_CARE',             'Борода та вуса',         'APPROVED', TRUE),
    ('SHAVING',                'Гоління',                'APPROVED', TRUE)
ON CONFLICT (name) DO UPDATE
    SET display_name = EXCLUDED.display_name,
        status       = EXCLUDED.status,
        active       = EXCLUDED.active;

-- ============================================================
-- Step 5: re-assert the renamed-slug rows via INSERT ... ON CONFLICT so that on
-- a CLEAN-DB replay (where the legacy HAIRCUT/MANICURE/EYELASH rows never
-- existed, so Step 1 matched zero rows) the new slugs are still created. On a
-- DB that already had the legacy rows, Step 1 renamed them and this becomes a
-- DO UPDATE no-op. This makes the migration deterministic on both paths.
-- ============================================================

INSERT INTO platform_categories (name, display_name, status, active) VALUES
    ('HAIRDRESSING',    'Перукарські послуги', 'APPROVED', TRUE),
    ('NAIL_SERVICE',    'Нігтьовий сервіс',    'APPROVED', TRUE),
    ('LASH_EXTENSIONS', 'Нарощення вій',       'APPROVED', TRUE),
    ('BROWS',           'Оформлення брів',     'APPROVED', TRUE),
    ('MAKEUP',          'Макіяж',              'APPROVED', TRUE)
ON CONFLICT (name) DO UPDATE
    SET display_name = EXCLUDED.display_name,
        status       = EXCLUDED.status,
        active       = EXCLUDED.active;
