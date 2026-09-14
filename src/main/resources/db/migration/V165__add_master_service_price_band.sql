-- Phase 311 — a salon master gets their own editable price band.
--
-- Supersedes part of Phase 302 D3 ("master_services carries only a price floor override, no
-- per-master ceiling or price type"). See docs/backend-phases/phase-311-per-master-price-band.md
-- for the full D1-D11 rationale; the supersession is recorded there, not in Phase 302's doc.
--
-- D2 — an assignment's band is ALL-OR-NOTHING: either fully inherited (all three columns NULL,
-- and the master tracks the shared service_definitions band), or fully specified locally (shape +
-- floor + ceiling all set, immune to later edits of the definition). No partial band is
-- representable — enforced below by chk_master_service_price_mode, not merely in Java.
--
-- D8 — measured against the local Docker database (the ONLY database this project has; by user
-- decision 2026-09-08, recorded in Phase 302/303, there is no production/Neon database to
-- re-measure against). Re-confirmed 2026-09-12 immediately before writing this migration:
--   master_services total rows                              14 308
--   ... with price_override IS NOT NULL                          48  <- the entire backfill population
--   ... of those, against a RANGE definition                      0
--   ... with duration_override_minutes IS NOT NULL                0
-- All 48 pre-existing overrides sit against FIXED definitions, so the backfill below sets
-- price_type_override = 'FIXED' and leaves price_max_override NULL for every one of them. The
-- CASE arm handling a RANGE definition is written anyway — it matches zero local rows, but this
-- statement has no separate "production" run to special-case, so it is written correctly the one
-- time it runs.

-- Same fail-fast pair as V164/V121/V137/V138/V141/V157, for the same reason: this migration gates
-- application startup on Railway and touches master_services, one of the hottest tables in the
-- schema. It takes AccessExclusiveLock TWICE (two ADD COLUMN) and then runs two VALIDATE
-- CONSTRAINT scans over the same table, so an unbounded lock wait here stalls the deploy behind
-- any long-running reader. lock_timeout bounds the WAIT for a lock (5s, then Flyway rolls back and
-- the next deploy retries); statement_timeout bounds EXECUTION once a lock is granted (1min per
-- statement — the backfill touches 48 rows locally and each VALIDATE is a single seq scan).
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '1min';

-- Fail fast (mirrors V67 MEDIUM-1): abort loudly if the backfill below would leave a row that
-- fails chk_master_service_price_mode, rather than letting VALIDATE CONSTRAINT fail with a bare
-- constraint name and no indication of which rows or why.
DO $$
DECLARE
    bad_count INTEGER;
BEGIN
    SELECT COUNT(*) INTO bad_count
    FROM master_services ms
    LEFT JOIN service_definitions sd ON sd.id = ms.service_def_id
    WHERE ms.price_override IS NOT NULL
      AND (sd.id IS NULL OR sd.price_type IS NULL);
    IF bad_count > 0 THEN
        RAISE EXCEPTION 'V165 aborted: % master_services row(s) carry price_override but their '
            'linked service_definitions row is missing or has a NULL price_type — the backfill '
            'cannot derive price_type_override for them, which would leave a partial band and '
            'trip chk_master_service_price_mode', bad_count;
    END IF;
END $$;

ALTER TABLE master_services ADD COLUMN price_type_override VARCHAR(10);
ALTER TABLE master_services ADD COLUMN price_max_override  NUMERIC(10,2);

-- D8's backfill — lifts every pre-existing floor-only override into the "own band" state so it
-- satisfies chk_master_service_price_mode below. Runs BEFORE the constraints are added/validated.
UPDATE master_services ms
   SET price_type_override = sd.price_type,
       price_max_override  = CASE WHEN sd.price_type = 'RANGE' THEN sd.price_max ELSE NULL END
  FROM service_definitions sd
 WHERE sd.id = ms.service_def_id
   AND ms.price_override IS NOT NULL
   AND ms.price_type_override IS NULL;

-- NOT VALID then VALIDATE (V67's pattern): defers the full-table scan to VALIDATE CONSTRAINT,
-- which runs under a ShareUpdateExclusiveLock rather than AccessExclusiveLock, keeping the table
-- available for reads/writes during validation.
ALTER TABLE master_services ADD CONSTRAINT chk_master_service_price_type
    CHECK (price_type_override IS NULL OR price_type_override IN ('FIXED', 'RANGE')) NOT VALID;
ALTER TABLE master_services VALIDATE CONSTRAINT chk_master_service_price_type;

-- D2 + D3 as one all-or-nothing predicate. Mirrors chk_service_def_price_mode (V67), including
-- its >= (not >) floor/ceiling comparison, so a degenerate RANGE band (floor == ceiling) is legal
-- at the DB layer exactly as it is for a definition.
--
-- SQL three-valued-logic trap (caught by MasterServiceBandBackfillIT case 30, mutation 17):
-- unlike service_definitions.price_type (NOT NULL since V67), price_type_override is legally
-- NULL for an Inherited row, so `price_type_override = 'FIXED'` evaluates to NULL — not FALSE —
-- whenever price_type_override IS NULL. A naive copy of V67's three-branch OR (each branch
-- comparing price_type_override to a literal with no NULL guard) would make the FIXED/RANGE
-- branches evaluate to NULL rather than FALSE for a genuinely partial band (price_type_override
-- NULL, price_override NOT NULL), and `FALSE OR NULL OR NULL` is NULL — which Postgres CHECK
-- constraints treat as PASSING (only an explicit FALSE rejects a row). The explicit
-- `price_type_override IS NOT NULL AND` guard on the FIXED/RANGE branches forces each to a
-- definite FALSE when the column is NULL (FALSE AND anything, including NULL, is FALSE), so the
-- overall expression correctly evaluates to FALSE — not NULL — for a partial band.
ALTER TABLE master_services ADD CONSTRAINT chk_master_service_price_mode CHECK (
    (price_type_override IS NULL AND price_override IS NULL AND price_max_override IS NULL)
 OR (price_type_override IS NOT NULL AND price_type_override = 'FIXED'
                                   AND price_override IS NOT NULL AND price_max_override IS NULL)
 OR (price_type_override IS NOT NULL AND price_type_override = 'RANGE'
                                   AND price_override IS NOT NULL AND price_max_override IS NOT NULL
                                   AND price_max_override >= price_override)
) NOT VALID;
ALTER TABLE master_services VALIDATE CONSTRAINT chk_master_service_price_mode;

COMMENT ON COLUMN master_services.price_type_override IS
    'Phase 311 — per-master pricing mode override: FIXED or RANGE. NULL means the assignment is '
    'Inherited (tracks service_definitions.price_type). All-or-nothing with price_override / '
    'price_max_override — see chk_master_service_price_mode.';
COMMENT ON COLUMN master_services.price_max_override IS
    'Phase 311 — per-master RANGE ceiling override. NULL for an Inherited or FIXED-own-band '
    'assignment. >= price_override when set (enforced).';
