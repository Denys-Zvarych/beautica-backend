-- Phase 3.1: Service catalogue
-- owner_type is polymorphic ('SALON' | 'INDEPENDENT_MASTER'); no FK to salons/masters
-- because a single service definition is owned by one entity type enforced at the app layer.

CREATE TABLE service_definitions (
    id                     UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_type             VARCHAR(20)  NOT NULL,
    owner_id               UUID         NOT NULL,
    name                   VARCHAR(255) NOT NULL,
    description            TEXT,
    category               VARCHAR(100),
    base_duration_minutes  INTEGER      NOT NULL,
    base_price             NUMERIC(10,2),
    buffer_minutes_after   INTEGER      NOT NULL DEFAULT 0,
    is_active              BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_service_def_owner_type CHECK (owner_type IN ('SALON', 'INDEPENDENT_MASTER')),
    CONSTRAINT chk_service_def_base_duration CHECK (base_duration_minutes > 0),
    CONSTRAINT chk_service_def_buffer CHECK (buffer_minutes_after >= 0),
    CONSTRAINT chk_service_def_base_price CHECK (base_price IS NULL OR base_price >= 0)
);

CREATE INDEX idx_service_def_owner    ON service_definitions (owner_type, owner_id);
CREATE INDEX idx_service_def_category ON service_definitions (category);
