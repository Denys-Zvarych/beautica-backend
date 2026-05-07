-- Composite partial index for category+active filter (replaces idx_service_types_category in purpose).
-- The old idx_service_types_category is intentionally left in place to avoid a Flyway conflict;
-- it will be cleaned up in a later migration once callers migrate to this index.
CREATE INDEX idx_service_types_category_active
    ON service_types (category_id, name_uk)
    WHERE is_active = true;

-- Sort index for service_categories to avoid full-table sort on ORDER BY sort_order.
CREATE INDEX idx_service_categories_sort_order
    ON service_categories (sort_order ASC);
