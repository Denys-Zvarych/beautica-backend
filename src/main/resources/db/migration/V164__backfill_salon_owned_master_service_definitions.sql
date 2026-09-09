-- Phase 303 — retrofit Phase 302's write-path rule onto data written before it existed.
--
-- Phase 302 (commit 4afede9) made POST /salons/{salonId}/masters/{masterId}/services/bulk persist
-- new definitions ownerType='SALON', ownerId=salonId. Every definition created through that
-- endpoint BEFORE 302 shipped still sits as ownerType='INDEPENDENT_MASTER', ownerId=<master.id>,
-- for a master whose masters.salon_id IS NOT NULL. MasterServiceRepository.findBookableAssignmentsBySalon
-- filters those out (sd.ownerType = SALON AND sd.ownerId = :salonId), so they are permanently
-- invisible in GET /salons/{salonId}/services — a two-tier catalogue keyed by creation date.
--
-- The discriminator is masters.salon_id IS NOT NULL, never owner_type alone: an INDEPENDENT_MASTER
-- row whose master has salon_id IS NULL is correct today and must be byte-identical after this runs
-- (phase doc D-nothing / test case 5).
--
-- See docs/backend-phases/phase-303-backfill-master-owned-salon-service-definitions.md for D1-D7
-- and the measured local-DB fixture (60 definitions, 1 salon, 5 masters, 12 service types, every
-- group a 5-way collision, ~40k bookings hanging off the master_services rows being repointed).

-- Same fail-fast pair as V121/V137/V138/V141/V157, for the same reason: this migration gates
-- application startup on Railway and touches the same hot tables (service_definitions,
-- master_services) those migrations already reasoned about. lock_timeout bounds the WAIT for a
-- lock (5s, then Flyway rolls back and the next deploy retries); statement_timeout bounds
-- EXECUTION once a lock is granted (1min per statement — this file has several UPDATEs plus two
-- temp-table builds, none of which scan more than the affected rows at the measured fixture size).
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '1min';

-- Step 1 — survivor selection per (salon_id, service_type_id) group (D2).
--
-- candidates: every ACTIVE INDEPENDENT_MASTER-owned definition belonging to a salon-bound master —
-- exactly the rows Phase 302 stopped creating and this migration retrofits.
--
-- pool: the candidates UNIONed with any pre-existing ACTIVE SALON-owned row for the same
-- (salon, service type) — D2's "if the salon already has an active SALON-owned row for that
-- service type, that row wins" branch (test case 4).
--
-- ranked: one ROW_NUMBER per group, ordering an existing SALON-owned row first, then OLDEST
-- created_at, then id — D2's "oldest (created_at, tie-broken by id) master-owned row wins" when no
-- SALON row already exists (test case 3: assert the id, not just the count).
--
-- A real (not merely "IF NOT EXISTS") DROP+CREATE makes this idempotent-by-construction even
-- within one session (D6, and the test harness's second-application check): a second run finds an
-- empty `candidates` CTE (every promoted survivor is already SALON-owned, so it no longer matches
-- the INDEPENDENT_MASTER predicate), so the temp table is empty and every statement below is a
-- true no-op.
DROP TABLE IF EXISTS v164_group_survivor;

CREATE TEMP TABLE v164_group_survivor AS
WITH candidates AS (
    SELECT sd.id,
           sd.service_type_id,
           m.salon_id,
           sd.created_at,
           sd.base_price,
           sd.base_duration_minutes,
           false AS is_salon_owned
    FROM service_definitions sd
             JOIN masters m ON m.id = sd.owner_id
    WHERE sd.owner_type = 'INDEPENDENT_MASTER'
      AND sd.is_active = true
      AND m.salon_id IS NOT NULL
),
groups AS (
    SELECT DISTINCT salon_id, service_type_id FROM candidates
),
pool AS (
    SELECT id, service_type_id, salon_id, created_at, base_price, base_duration_minutes, is_salon_owned
    FROM candidates
    UNION ALL
    SELECT sd.id, sd.service_type_id, g.salon_id, sd.created_at, sd.base_price, sd.base_duration_minutes, true
    FROM groups g
             JOIN service_definitions sd
                  ON sd.service_type_id = g.service_type_id
                      AND sd.owner_type = 'SALON'
                      AND sd.owner_id = g.salon_id
                      AND sd.is_active = true
),
ranked AS (
    SELECT p.*,
           ROW_NUMBER() OVER (
               PARTITION BY salon_id, service_type_id
               ORDER BY is_salon_owned DESC, created_at ASC, id ASC
               ) AS rn
    FROM pool p
)
SELECT salon_id,
       service_type_id,
       id                     AS survivor_id,
       base_price             AS survivor_base_price,
       base_duration_minutes  AS survivor_base_duration_minutes
FROM ranked
WHERE rn = 1;

-- Step 2 — map every candidate (loser AND the survivor itself, harmlessly, when survivor_id =
-- loser_id) to its group's survivor. Recomputed fresh from service_definitions/masters rather than
-- reused from step 1's candidates CTE, so it reflects the same pre-mutation snapshot deterministically.
DROP TABLE IF EXISTS v164_loser_map;

CREATE TEMP TABLE v164_loser_map AS
SELECT c.id                                   AS loser_id,
       c.salon_id,
       c.service_type_id,
       c.base_price                           AS loser_base_price,
       c.base_duration_minutes                AS loser_base_duration_minutes,
       gs.survivor_id,
       gs.survivor_base_price,
       gs.survivor_base_duration_minutes
FROM (
         SELECT sd.id, m.salon_id, sd.service_type_id, sd.base_price, sd.base_duration_minutes
         FROM service_definitions sd
                  JOIN masters m ON m.id = sd.owner_id
         WHERE sd.owner_type = 'INDEPENDENT_MASTER'
           AND sd.is_active = true
           AND m.salon_id IS NOT NULL
     ) c
         JOIN v164_group_survivor gs
              ON gs.salon_id = c.salon_id AND gs.service_type_id = c.service_type_id;

-- Step 3 — D4 dedupe BEFORE repointing. master_services carries a FULL (not partial)
-- UNIQUE(master_id, service_def_id) (V7:15), so if a master already holds ANY row — active or not —
-- against the survivor, repointing its loser-pointed row would collide. Deactivate the duplicate
-- assignment instead of repointing it, per D4: the row is left pointing at the (now-deactivated)
-- loser definition, is_active=false, never touching service_def_id. V121's own defensive dedupe
-- before its unique index is the precedent this mirrors (see that file's header).
UPDATE master_services ms
SET is_active  = false,
    updated_at = now()
FROM v164_loser_map lm
WHERE ms.service_def_id = lm.loser_id
  AND lm.survivor_id <> lm.loser_id
  AND ms.is_active = true
  AND EXISTS (SELECT 1
              FROM master_services ms2
              WHERE ms2.master_id = ms.master_id
                AND ms2.service_def_id = lm.survivor_id);

-- Step 4 — repoint every remaining master_services row off a loser onto the survivor (D2 step 2),
-- carrying the per-master price/duration divergence into the assignment's overrides (D5) so a
-- future booking prices the same as it would have under the loser. "Remaining" excludes exactly the
-- rows step 3 just deactivated (those keep pointing at the loser to avoid the UNIQUE collision).
--
-- COALESCE(ms.price_override, lm.loser_base_price) is the master's EFFECTIVE price under the loser
-- before this statement touches it; IS DISTINCT FROM lm.survivor_base_price is D5's "when it
-- differs" test (NULL-safe, though base_price is NOT NULL-checked >= 0 elsewhere it can be legally
-- NULL per V6). Same shape for duration.
UPDATE master_services ms
SET service_def_id             = lm.survivor_id,
    price_override             = CASE
                                      WHEN COALESCE(ms.price_override, lm.loser_base_price)
                                          IS DISTINCT FROM lm.survivor_base_price
                                          THEN COALESCE(ms.price_override, lm.loser_base_price)
                                      ELSE NULL
        END,
    duration_override_minutes  = CASE
                                      WHEN COALESCE(ms.duration_override_minutes, lm.loser_base_duration_minutes)
                                          IS DISTINCT FROM lm.survivor_base_duration_minutes
                                          THEN COALESCE(ms.duration_override_minutes, lm.loser_base_duration_minutes)
                                      ELSE NULL
        END,
    updated_at                 = now()
FROM v164_loser_map lm
WHERE ms.service_def_id = lm.loser_id
  AND lm.survivor_id <> lm.loser_id
  AND NOT EXISTS (SELECT 1
                  FROM master_services ms2
                  WHERE ms2.master_id = ms.master_id
                    AND ms2.service_def_id = lm.survivor_id);

-- Step 5 — promote the survivor to SALON ownership (D2 step 3). No-op when the survivor was
-- already SALON-owned (the owner_type predicate simply matches nothing).
UPDATE service_definitions sd
SET owner_type = 'SALON',
    owner_id   = gs.salon_id,
    updated_at = now()
FROM v164_group_survivor gs
WHERE sd.id = gs.survivor_id
  AND sd.owner_type = 'INDEPENDENT_MASTER';

-- Step 6 — deactivate the losers (D3: NEVER DELETE — master_services.service_def_id ... ON DELETE
-- CASCADE (V7:9) combined with bookings.master_service_id ... NO ACTION (V18:7) means a hard delete
-- of a loser definition would cascade-orphan its master_services rows, which bookings then refuses
-- to release, aborting the migration mid-flight on a table it had already partly rewritten).
-- By this point every group's survivor has already been promoted to SALON in step 5, so every row
-- still matching the INDEPENDENT_MASTER + salon-bound predicate here IS a loser by construction —
-- no need to re-exclude the survivor id explicitly.
UPDATE service_definitions sd
SET is_active  = false,
    updated_at = now()
FROM v164_loser_map lm
WHERE sd.id = lm.loser_id
  AND lm.survivor_id <> lm.loser_id
  AND sd.is_active = true;

DROP TABLE IF EXISTS v164_loser_map;
DROP TABLE IF EXISTS v164_group_survivor;
