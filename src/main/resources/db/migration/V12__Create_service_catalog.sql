-- Enable trigram extension for fuzzy search on service type names
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE service_categories (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name_uk     VARCHAR(100) NOT NULL,
    name_en     VARCHAR(100) NOT NULL,
    sort_order  INTEGER NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE service_types (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    category_id UUID NOT NULL REFERENCES service_categories(id) ON DELETE RESTRICT,
    name_uk     VARCHAR(255) NOT NULL,
    name_en     VARCHAR(255),
    slug        VARCHAR(255) NOT NULL UNIQUE,
    is_active   BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Trigram indexes for autocomplete search
CREATE INDEX idx_service_types_name_uk_trgm ON service_types USING gin (name_uk gin_trgm_ops);
CREATE INDEX idx_service_types_name_en_trgm ON service_types USING gin (name_en gin_trgm_ops);
CREATE INDEX idx_service_types_category    ON service_types (category_id);
