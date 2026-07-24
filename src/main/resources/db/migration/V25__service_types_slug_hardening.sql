-- Rename the implicit slug uniqueness constraint to follow project naming convention.
ALTER TABLE service_types RENAME CONSTRAINT service_types_slug_key TO uq_service_types_slug;

-- Enforce URL-safe slug format: lowercase alphanumeric, dashes, no leading/trailing dash.
ALTER TABLE service_types
    ADD CONSTRAINT chk_service_types_slug
    CHECK (slug ~ '^[a-z0-9][a-z0-9\-]*[a-z0-9]$');
