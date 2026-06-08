-- Phase 16.x: optional "initial service name" carried on a self-service category request.
--
-- One submission can request both a new platform category and (optionally) an initial
-- service-type name. On admin approval of the category the carried name becomes a
-- PENDING service_type_suggestion (no auto-promotion; matches Phase 16.8). The column
-- is nullable with no default: the seed/ops-CRUD approval path never sets it.
--
-- Idempotent: ADD COLUMN IF NOT EXISTS so a clean-DB replay yields an identical result
-- and a re-apply is a no-op.

ALTER TABLE platform_categories
    ADD COLUMN IF NOT EXISTS initial_service_name VARCHAR(255);

-- Control-char ban mirroring the DTO @Pattern("^[^\p{Cntrl}]*$"). The DB length cap
-- is not a format check; this rejects control characters reaching the column via any
-- write path. NULL passes (optional field).
ALTER TABLE platform_categories
    DROP CONSTRAINT IF EXISTS chk_platform_categories_initial_service_name;
ALTER TABLE platform_categories
    ADD CONSTRAINT chk_platform_categories_initial_service_name
        CHECK (initial_service_name IS NULL OR initial_service_name !~ '[[:cntrl:]]');
