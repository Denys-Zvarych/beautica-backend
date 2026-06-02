-- V67: flexible service pricing — FIXED (single amount) or RANGE (min..max).
-- base_price retained as canonical floor: FIXED -> the amount; RANGE -> the minimum.
-- Keeps masters.min_effective_price (V58) and bookings.price_at_booking working unchanged.

-- MEDIUM-1 guard: abort immediately if any existing row has NULL base_price.
-- The chk_service_def_price_mode constraint below requires base_price IS NOT NULL for both
-- pricing modes, so a NULL row would crash-loop Flyway on the ADD CONSTRAINT step.
-- Backfill any NULL rows before deploying this migration.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM service_definitions WHERE base_price IS NULL) THEN
        RAISE EXCEPTION 'V67 aborted: % row(s) have NULL base_price — backfill before adding price-mode constraint',
            (SELECT COUNT(*) FROM service_definitions WHERE base_price IS NULL);
    END IF;
END $$;

-- Add price_type column, backfill existing rows, then lock it down.
ALTER TABLE service_definitions ADD COLUMN price_type VARCHAR(10);
UPDATE service_definitions SET price_type = 'FIXED' WHERE price_type IS NULL;
ALTER TABLE service_definitions
    ALTER COLUMN price_type SET NOT NULL,
    ALTER COLUMN price_type SET DEFAULT 'FIXED';

-- MEDIUM-2: NOT VALID defers the full-table scan to VALIDATE CONSTRAINT, which runs
-- under a ShareUpdateExclusiveLock rather than AccessExclusiveLock, keeping the table
-- available for reads/writes during validation.
ALTER TABLE service_definitions ADD CONSTRAINT chk_service_def_price_type
    CHECK (price_type IN ('FIXED', 'RANGE')) NOT VALID;
ALTER TABLE service_definitions VALIDATE CONSTRAINT chk_service_def_price_type;

-- MEDIUM-1: Guard above guarantees no NULLs exist; enforce the column invariant at the DB level.
ALTER TABLE service_definitions ALTER COLUMN base_price SET NOT NULL;

-- Add optional RANGE ceiling column.
ALTER TABLE service_definitions ADD COLUMN price_max NUMERIC(10,2);

-- MEDIUM-2: Same NOT VALID + VALIDATE pattern for the composite mode constraint.
-- NOTE: chk_service_def_price_mode intentionally uses >= (not >) for the RANGE floor/ceiling
-- comparison — the application validator enforces strict > at the service layer.
ALTER TABLE service_definitions ADD CONSTRAINT chk_service_def_price_mode CHECK (
    (price_type = 'FIXED' AND base_price IS NOT NULL AND price_max IS NULL)
    OR
    (price_type = 'RANGE' AND base_price IS NOT NULL AND price_max IS NOT NULL AND price_max >= base_price)
) NOT VALID;
ALTER TABLE service_definitions VALIDATE CONSTRAINT chk_service_def_price_mode;

COMMENT ON COLUMN service_definitions.price_type IS 'Pricing mode: FIXED (base_price only) or RANGE (base_price=min, price_max=max).';
COMMENT ON COLUMN service_definitions.price_max IS 'RANGE ceiling. NULL for FIXED. NUMERIC(10,2). >= base_price (enforced).';
