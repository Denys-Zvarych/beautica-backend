ALTER TABLE service_definitions
    ADD COLUMN service_type_id UUID REFERENCES service_types(id) ON DELETE SET NULL;

CREATE INDEX idx_service_def_service_type ON service_definitions(service_type_id);
