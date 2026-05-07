-- ─── H1: Drop old single-column category index (superseded by the composite partial
--         index created in V14: idx_service_types_category_active) ──────────────────
DROP INDEX IF EXISTS idx_service_types_category;

-- ─── H2: Unique constraint on service_categories.sort_order ──────────────────────
-- Ensures the UI sort position is unambiguous. Existing seed data has unique values 1–8.
-- NULL values are excluded from uniqueness checks in PostgreSQL (each NULL is distinct),
-- so rows with sort_order IS NULL are still accepted without violating this constraint.
ALTER TABLE service_categories
    ADD CONSTRAINT uq_service_categories_sort_order UNIQUE (sort_order);

-- ─── H3: Make name_en NOT NULL with DEFAULT '' in service_types ──────────────────
-- Without this, the GIN trigram index on name_en silently skips NULL rows, producing
-- incomplete search results for English-language queries.
UPDATE service_types SET name_en = '' WHERE name_en IS NULL;
ALTER TABLE service_types ALTER COLUMN name_en SET NOT NULL;
ALTER TABLE service_types ALTER COLUMN name_en SET DEFAULT '';
