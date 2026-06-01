-- Phase X: Platform-managed service categories table.
--
-- The ServiceCategory Java enum is superseded by this table as the authoritative
-- source of valid category values. The service_definitions.category column
-- (VARCHAR(100)) stores the name string and is validated at the service layer
-- against this table. No FK is declared because the polymorphic write path
-- (salon + independent master) would require deferrable FKs; the service layer
-- enforces the integrity check instead.
--
-- Naming rule for `name`: uppercase letters, digits, and underscores only;
-- must start with an uppercase letter. Enforced by the check constraint and
-- by Bean Validation (@Pattern) on the request DTO.

CREATE TABLE platform_categories (
    id         BIGSERIAL    PRIMARY KEY,
    name       VARCHAR(100) NOT NULL,
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_platform_categories_name UNIQUE (name),
    CONSTRAINT chk_platform_categories_name
        CHECK (name ~ '^[A-Z][A-Z0-9_]*$')
);

-- Seed all current ServiceCategory enum values so the table is the
-- authoritative source from day one and existing service_definitions rows
-- that carry these strings remain valid.
INSERT INTO platform_categories (name) VALUES
    ('MANICURE'),
    ('PEDICURE'),
    ('EYELASH'),
    ('HAIRCUT'),
    ('MAKEUP'),
    ('BROWS'),
    ('OTHER');
