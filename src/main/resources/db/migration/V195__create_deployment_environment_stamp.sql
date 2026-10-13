-- Phase 368 (F5): singleton stamp recording which deployment environment owns this database, so a
-- staging container pointed at the prod DATABASE_URL (or the reverse) fails at boot. Host/db name cannot
-- distinguish the two Railway environments. Written by DeploymentEnvironmentStamp; local/test never stamp.
CREATE TABLE deployment_environment (
    id          BOOLEAN     PRIMARY KEY DEFAULT TRUE CHECK (id),
    environment TEXT        NOT NULL CHECK (environment IN ('staging', 'prod')),
    stamped_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
