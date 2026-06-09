-- V81: Reconcile the live taxonomy to the user's FINAL 20-category list (track 16.x/17.x).
--
-- Context
-- -------
-- The live taxonomy (V74 platform_categories + V75 service_types) is ~98% correct.
-- This is a SMALL reconciliation, NOT a rebuild. The user's final list collapses the
-- standalone 21st category "Гоління" (SHAVING) into "Борода та вуса" (BEARD_CARE):
-- shaving is a sub-service of beard care, leaving exactly 20 active categories.
--
-- What this migration does
-- ------------------------
--   1. Fold SHAVING -> BEARD_CARE at the service_types level: re-parent the shaving
--      leaf services to platform_category_name = 'BEARD_CARE' (category_id = Hair
--      bucket 0004, unchanged) AND rename their slugs from 'shaving-*' to 'beard-*'
--      so they match the parent category's slug convention.
--   2. Ensure the full SHAVING leaf set exists under BEARD_CARE (idempotent upsert of
--      all 5 beard-* shaving rows — covers both the V75-live path, where all 5
--      shaving-* rows already exist and are merely renamed in step 1, and the
--      3-existing path, where 'Контурне гоління' / 'Гоління шиї' are net-new).
--   3. Delete the stray 'nail-service-pedicure-toes' leaf (not in the final list).
--   4. Soft-disable the SHAVING platform_categories row (active = FALSE) so it drops
--      from the picker and the validate-category gate, leaving 20 active categories.
--
-- Renaming slug is FK-safe: NO foreign key references service_types.slug.
-- service_definitions.service_type_id FKs on service_types.id with ON DELETE SET NULL
-- (V15), so re-pointing platform_category_name and renaming slug never break a ref,
-- and the pedicure-toes hard DELETE only nulls any (≈0 pre-launch) referencing row.
--
-- FK-safe ordering
-- ----------------
-- All re-parenting/inserting of children under BEARD_CARE happens FIRST, while the
-- BEARD_CARE platform_categories row already exists (V74). The V73 FK
-- (fk_service_types_platform_category) is ON UPDATE CASCADE ON DELETE RESTRICT, so
-- re-pointing platform_category_name to an existing category is legal. SHAVING is
-- only soft-disabled (active = FALSE), never deleted, so the FK (and the UNIQUE(name)
-- workflow guard) is never violated.
--
-- Idempotency / determinism
-- -------------------------
--   * The shaving-* -> beard-* renames are guarded by WHERE slug = '<shaving slug>',
--     so a second apply (rows already renamed) matches zero rows -> no-op.
--   * The beard-* upsert uses ON CONFLICT (slug) DO NOTHING -> re-apply is a no-op.
--   * The re-parent UPDATE is self-idempotent (re-setting the same value is a no-op).
--   * The pedicure-toes DELETE matches zero rows on replay.
--   * The SHAVING soft-disable UPDATE is self-idempotent.
-- A clean-DB replay yields an identical final row set.
--
-- !! APP RESTART / CACHE NOTE !!
-- -----------------------------
-- This is pure SQL. It does NOT refresh the running app's Caffeine caches
-- 'service-categories', 'service-types', and 'service-type-by-id'. After this
-- migration deploys, the app MUST be restarted (or those caches evicted) or the
-- picker/lookups will serve the stale pre-V81 taxonomy until the TTL expires.
-- (Same reseed-staleness footgun as V74/V75.)
--
-- Slug rename map (shaving-* -> beard-*), Hair bucket 0004:
--   shaving-royal   -> beard-royal-shave     (Королівське гоління (небезпечна бритва))
--   shaving-classic -> beard-classic-shave   (Класичне гоління)
--   shaving-head    -> beard-head-shave      (Гоління голови)
--   shaving-contour -> beard-contour-shave   (Контурне гоління)
--   shaving-neck    -> beard-neck-shave      (Гоління шиї)

-- ============================================================
-- Step 1: re-parent + rename the shaving leaf services under BEARD_CARE.
-- platform_category_name -> 'BEARD_CARE' (Hair bucket 0004 is already correct).
-- slug 'shaving-*' -> 'beard-*' to match the BEARD_CARE slug convention.
-- Guarded per-slug so a re-apply (already renamed) is a zero-row no-op.
-- ============================================================

UPDATE service_types
SET    platform_category_name = 'BEARD_CARE',
       slug                   = 'beard-royal-shave'
WHERE  slug = 'shaving-royal';

UPDATE service_types
SET    platform_category_name = 'BEARD_CARE',
       slug                   = 'beard-classic-shave'
WHERE  slug = 'shaving-classic';

UPDATE service_types
SET    platform_category_name = 'BEARD_CARE',
       slug                   = 'beard-head-shave'
WHERE  slug = 'shaving-head';

UPDATE service_types
SET    platform_category_name = 'BEARD_CARE',
       slug                   = 'beard-contour-shave'
WHERE  slug = 'shaving-contour';

UPDATE service_types
SET    platform_category_name = 'BEARD_CARE',
       slug                   = 'beard-neck-shave'
WHERE  slug = 'shaving-neck';

-- ============================================================
-- Step 2: ensure all 5 beard-* shaving leaves exist under BEARD_CARE.
-- On the V75-live path Step 1 already renamed them, so every row below conflicts
-- on (slug) and is a DO NOTHING no-op. On the 3-existing path, 'beard-contour-shave'
-- and 'beard-neck-shave' are net-new and get inserted here. Hair bucket 0004.
-- ============================================================

INSERT INTO service_types (category_id, platform_category_name, slug, name_uk, name_en, is_active) VALUES
  ('11111111-0004-0000-0000-000000000000', 'BEARD_CARE', 'beard-royal-shave',   'Королівське гоління (небезпечна бритва)', 'Royal Shave (Straight Razor)', TRUE),
  ('11111111-0004-0000-0000-000000000000', 'BEARD_CARE', 'beard-classic-shave', 'Класичне гоління',                        'Classic Shave',                TRUE),
  ('11111111-0004-0000-0000-000000000000', 'BEARD_CARE', 'beard-head-shave',    'Гоління голови',                          'Head Shave',                   TRUE),
  ('11111111-0004-0000-0000-000000000000', 'BEARD_CARE', 'beard-contour-shave', 'Контурне гоління',                        'Contour Shave',                TRUE),
  ('11111111-0004-0000-0000-000000000000', 'BEARD_CARE', 'beard-neck-shave',    'Гоління шиї',                             'Neck Shave',                   TRUE)
ON CONFLICT (slug) DO NOTHING;

-- ============================================================
-- Step 3: delete the stray leaf not present in the final list.
-- FK service_definitions.service_type_id is ON DELETE SET NULL (V15); ≈0 rows
-- pre-launch. Re-apply matches zero rows -> no-op.
-- ============================================================

DELETE FROM service_types
WHERE  slug = 'nail-service-pedicure-toes';

-- ============================================================
-- Step 4: soft-disable the now-folded SHAVING platform_category.
-- NOT a DELETE: the UNIQUE(name) workflow guard + V73 FK precedent (V74 Step 3)
-- keep the row, set active = FALSE so it drops from the picker and the
-- validate-category gate. Leaves exactly 20 active categories. Self-idempotent.
-- ============================================================

UPDATE platform_categories
SET    active = FALSE
WHERE  name = 'SHAVING';
