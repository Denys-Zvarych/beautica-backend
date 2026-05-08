-- Composite partial index for salons to cover findAllByOwnerIdAndIsActiveTrue.
-- Filters directly on is_active = TRUE to avoid scanning inactive rows.
CREATE INDEX IF NOT EXISTS idx_salons_owner_active
    ON salons(owner_id, is_active)
    WHERE is_active = TRUE;

-- Drop the single-column idx_service_types_category created in V12.
-- It is superseded by the composite partial index idx_service_types_category_active
-- added in V14, which covers all callers that filter on category_id + is_active.
DROP INDEX IF EXISTS idx_service_types_category;
