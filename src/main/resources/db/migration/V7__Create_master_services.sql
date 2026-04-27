-- Phase 3.1: Per-master service assignments with optional price/duration overrides.
-- A master can offer any service_definition owned by their salon or (for independent masters)
-- owned by themselves.  The unique constraint on (master_id, service_def_id) prevents
-- duplicate assignments.

CREATE TABLE master_services (
    id                        UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    master_id                 UUID         NOT NULL REFERENCES masters(id) ON DELETE CASCADE,
    service_def_id            UUID         NOT NULL REFERENCES service_definitions(id) ON DELETE CASCADE,
    price_override            NUMERIC(10,2),
    duration_override_minutes INTEGER,
    is_active                 BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (master_id, service_def_id),
    CONSTRAINT chk_master_service_price_override    CHECK (price_override IS NULL OR price_override >= 0),
    CONSTRAINT chk_master_service_duration_override CHECK (duration_override_minutes IS NULL OR duration_override_minutes > 0)
);

CREATE INDEX idx_master_services_master      ON master_services (master_id);
CREATE INDEX idx_master_services_service_def ON master_services (service_def_id);
