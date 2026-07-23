-- One ACTIVE service per (owner, service type).
--
-- PRODUCT RULE: a master (or salon) must not be able to create the same service twice.
-- «Класичне нарощення» already in the menu => creating it again is a 409, regardless of the
-- price or duration entered. Two rows of the same service type with different prices ARE
-- duplicates by the locked product decision.
--
-- WHY service_type_id AND NOT name:
--   * service_type_id has been NOT NULL since V111, so every row carries the key.
--   * the displayed name is DERIVED from the type — ServiceCatalogService#resolveCreateName
--     defaults to serviceType.nameUk, and the bulk path always uses serviceType.nameUk
--     (BulkServiceItemRequest carries no name field at all). Keying on the name would let a
--     caller bypass the rule with a custom name, and would drag in case/trim/Ukrainian
--     collation concerns this schema has never needed (no LOWER()/citext/COLLATE anywhere in
--     the migration history — this migration deliberately does not introduce the first one).
--   * `category` is functionally determined by the type (resolveServiceType rejects a type
--     outside the target category; the bulk path derives the category FROM the type), so
--     adding it to the key would only widen it — never tighten it.
--
-- WHY PARTIAL ON is_active = true (mandatory, not a nicety):
--   deletion is SOFT — ServiceRepository#deactivateById flips is_active = false and the row
--   survives. An UNCONDITIONAL unique index would mean that deleting «Класичне нарощення»
--   once makes it permanently uncreatable, with no escape hatch (UpdateServiceDefinitionRequest
--   exposes no isActive field, and there is no reactivate endpoint). A deactivated row must
--   neither block re-creation nor be resurrected — the create path inserts a fresh row.

-- Fail fast rather than queue, and cap PER-STATEMENT execution — the SAME pair V120 sets, for the
-- same reasons, and mandatory here because this file does strictly MORE work than either sibling.
--
-- lock_timeout bounds the WAIT for a lock. Step 2's `CREATE UNIQUE INDEX` (NOT CONCURRENTLY —
-- see its own note) takes SHARE on `service_definitions`, which conflicts with the ROW EXCLUSIVE
-- every ordinary INSERT/UPDATE holds; steps 1/1b take ROW EXCLUSIVE on `service_definitions` and
-- `masters`. Postgres lock requests are FIFO, so a pending SHARE request does not merely stall the
-- deploy — it parks every subsequent write on the service catalogue behind it while the old
-- instance is still serving traffic through a Railway rolling deploy. 5s, then error out: Flyway
-- rolls this transaction back cleanly and the next deploy retries it. Same value, same argument as
-- V119:37.
SET LOCAL lock_timeout = '5s';

-- statement_timeout bounds EXECUTION TIME, which lock_timeout never does (it stops firing the
-- moment a lock is granted). Everything below is unbounded-by-table-size work that acquires its
-- locks instantly and then runs: a CTAS window scan, two UPDATEs (one with a correlated MIN
-- subquery) and a full index build. With ZERO lock contention the only remaining bound would be
-- connection death — precisely the startup crash-loop class documented in memory
-- `project_flyway_checksum_recovery.md`, since this transaction gates application startup.
--
-- READ THE UNIT LITERALLY: Postgres applies statement_timeout PER STATEMENT, not per transaction —
-- it is re-armed for each one and there is no transaction-wide equivalent. Five statements follow
-- this SET LOCAL (the CTAS, the service_definitions UPDATE, the DO block, the masters UPDATE, the
-- CREATE UNIQUE INDEX), so the ceiling this line buys is 1min EACH and therefore ~5min for the
-- whole migration — which IS the intended deploy ceiling, since a Railway healthcheck timeout is
-- driven by total startup time, not by any single statement. The value is 1min rather than V120's
-- 5min precisely so the SUM lands at the same place: at the table sizes this migration was sized
-- for every statement here is seconds, so 1min is still ~1 order of magnitude of headroom on a
-- healthy run and fires only on a genuinely pathological plan. Both settings are SET LOCAL, so
-- both revert when Flyway commits this migration — no leakage into the pooled connection.
SET LOCAL statement_timeout = '1min';

-- Step 0 — AUDIT TRAIL for step 1, written BEFORE anything is destroyed.
--
-- Step 1 below soft-deactivates rows a provider created and may still be actively using.
-- Deactivation is silent and, without a record of WHICH rows were picked, unexplainable after
-- the fact — support could neither answer nor undo "my price disappeared". This table IS the
-- record. It is deliberately a permanent table (not TEMP): a TEMP table dies with the Flyway
-- session, taking the evidence with it.
--
-- Every logged loser also carries `survivor_id` — the row that KEPT the (owner, type) slot.
-- Without it support can state that a service was deactivated but not what replaced it, and
-- re-deriving the survivor after the fact is impossible: the window function below reads the
-- pre-migration is_active population, which step 1 immediately destroys. Recording it costs one
-- extra FIRST_VALUE over the SAME window (Postgres evaluates one WindowAgg for both functions
-- because they share the named window `dedup_window` verbatim), so it adds no scan and no extra
-- evaluation of the correlated EXISTS.
--
-- Survivor selection (the ORDER BY inside dedup_window) prefers a row that carries BOOKING
-- HISTORY over one that does not. Bookings reference master_services.id, which references
-- service_def_id — so a definition with bookings is the one a real client actually transacted
-- against, and deactivating it is strictly worse than deactivating an untouched twin.
-- Only when the booking signal ties do the original recency tie-breaks apply
-- (updated_at, created_at, id — the last purely for determinism when seeded fixtures share
-- timestamps).
--
-- PRE-FILTERED TO REAL COLLISION GROUPS (`collision_groups`), not merely "every active row".
-- The window's leading ORDER BY term is a CORRELATED EXISTS, evaluated once per row it ranks.
-- Ranking the whole active population would run that subquery for every singleton partition too
-- — rows that are alone in their (owner, type) slot and therefore can never be a loser (rn is
-- always 1, so the `rn > 1` filter discards them anyway). On a collision-free database — which
-- is every environment except the one local DB that motivated this migration, and every replay
-- on a restored snapshot — that is the entire table's worth of pure waste, inside the
-- transaction that gates application startup. The GROUP BY/HAVING pre-pass is one cheap
-- aggregate over the same partial-index-able predicate and reduces the window's input to
-- exactly the rows that can lose. Semantics are unchanged: partitions of size 1 contribute
-- nothing to the result either way.
--
-- NOT registered in AbstractIntegrationTest.cleanDb (anti-bug §O-7) on purpose: no application
-- code ever writes here, and on a fresh test database step 1 finds nothing, so the table is
-- always empty in tests. Deleting from it would be deleting production forensics.
CREATE TABLE v121_dedup_log AS
WITH collision_groups AS (
    SELECT sd.owner_type,
           sd.owner_id,
           sd.service_type_id
    FROM service_definitions sd
    WHERE sd.is_active = true
    GROUP BY sd.owner_type, sd.owner_id, sd.service_type_id
    HAVING COUNT(*) > 1
),
ranked AS (
    SELECT sd.id,
           sd.owner_type,
           sd.owner_id,
           sd.service_type_id,
           FIRST_VALUE(sd.id) OVER dedup_window AS survivor_id,
           ROW_NUMBER() OVER dedup_window        AS rn
    FROM service_definitions sd
             JOIN collision_groups cg
                  ON cg.owner_type = sd.owner_type
                      AND cg.owner_id = sd.owner_id
                      AND cg.service_type_id = sd.service_type_id
    WHERE sd.is_active = true
    WINDOW dedup_window AS (
        PARTITION BY sd.owner_type, sd.owner_id, sd.service_type_id
        ORDER BY (EXISTS (SELECT 1
                          FROM master_services ms
                                   JOIN bookings b ON b.master_service_id = ms.id
                          WHERE ms.service_def_id = sd.id)) DESC,
            sd.updated_at DESC,
            sd.created_at DESC,
            sd.id DESC
        )
)
SELECT id,
       owner_type,
       owner_id,
       service_type_id,
       survivor_id,
       now() AS deactivated_at
FROM ranked
WHERE rn > 1;

COMMENT ON TABLE v121_dedup_log IS
    'Forensic record of the ServiceDefinition rows V121 soft-deactivated to satisfy ux_service_def_owner_service_type_active. Written before the UPDATE; never touched by application code. Survivor was chosen preferring rows with booking history, then updated_at/created_at/id DESC, and its id is recorded per row in survivor_id.';

COMMENT ON COLUMN v121_dedup_log.survivor_id IS
    'The service_definitions row that KEPT this (owner_type, owner_id, service_type_id) slot. Support answers "what replaced my service" from this column - it is not re-derivable once step 1 has run.';

-- Step 1 — dedupe existing ACTIVE collisions BEFORE the index is created.
-- MANDATORY: CREATE UNIQUE INDEX aborts on the first violation, and a failed Flyway migration
-- is a Railway startup crash-loop. The local DB does currently hold a collision group
-- (4 active rows for one INDEPENDENT_MASTER + service type), so this is not hypothetical.
-- The losers are exactly the rows step 0 recorded, soft-deactivated exactly as the
-- application's own delete path would have done — joining the log instead of re-running the
-- window function guarantees the audit trail and the mutation can never disagree.
UPDATE service_definitions sd
SET is_active  = false,
    updated_at = now()
FROM v121_dedup_log d
WHERE sd.id = d.id;

-- Step 1a — OPERATOR SIGNAL. Everything above mutates provider-owned rows silently: Flyway logs
-- "Migrating schema public to version 121" and nothing else, so a deploy that deactivated real
-- services looks byte-identical in the log to one that found none. RAISE NOTICE puts the count on
-- the deploy log, which is where an operator actually looks when a provider reports a missing
-- service. A DO block is required because RAISE is PL/pgSQL, not SQL.
--
-- Zero is the expected value everywhere except the one database that motivated this migration, so
-- a non-zero line here is itself the alert. Deliberately NOT a RAISE EXCEPTION or an assertion: a
-- collision found is the case this migration EXISTS to repair, not a failure.
DO
$$
    DECLARE
        deactivated_count bigint;
    BEGIN
        SELECT count(*) INTO deactivated_count FROM v121_dedup_log;
        RAISE NOTICE
            'V121: soft-deactivated % duplicate ACTIVE service_definitions row(s); losers and their survivor_id are recorded in v121_dedup_log',
            deactivated_count;
    END
$$;

-- Step 1b — masters.min_effective_price is a denormalised MIN over ACTIVE definitions
-- (V58), so deactivating a duplicate above can leave it stale. Recompute it. Mirrors
-- MasterRepository#refreshMinEffectivePriceForAll exactly.
--
-- Deliberately a SEPARATE statement, not a data-modifying CTE chained onto step 1: a CTE
-- reads the statement-start snapshot, so the rows step 1 just deactivated would still read
-- as active and the recomputed MIN would be the stale one it is meant to fix.
--
-- Scope is EXACTLY the masters step 1 affected — join v121_dedup_log, never
-- "every master holding an assignment to some inactive definition". That superset grows
-- monotonically with normal soft-deletes over the app's whole lifetime and is unrelated to
-- what this migration changed, so it would make an unbounded number of rows each run a
-- correlated MIN subquery inside the Flyway transaction that gates application startup —
-- on Railway a slow migration is a healthcheck-timeout deploy failure. On a database with
-- no collisions the log is empty and this statement touches zero rows.
UPDATE masters m
SET min_effective_price = (
        SELECT MIN(COALESCE(ms.price_override, sd.base_price))
        FROM master_services ms
                 JOIN service_definitions sd ON sd.id = ms.service_def_id
        WHERE ms.master_id = m.id
          AND ms.is_active = true
          AND sd.is_active = true
    )
WHERE m.id IN (SELECT ms.master_id
               FROM master_services ms
                        JOIN v121_dedup_log d ON d.id = ms.service_def_id);

-- Step 2 — the constraint itself.
-- Plain CREATE UNIQUE INDEX, NOT CONCURRENTLY: Flyway wraps each migration in a transaction
-- and CREATE INDEX CONCURRENTLY cannot run inside one.
CREATE UNIQUE INDEX ux_service_def_owner_service_type_active
    ON service_definitions (owner_type, owner_id, service_type_id)
    WHERE is_active = true;

COMMENT ON INDEX ux_service_def_owner_service_type_active IS
    'One ACTIVE service per (owner_type, owner_id, service_type_id). Partial on is_active = true so a soft-deleted service can be re-created. Backs the DUPLICATE_SERVICE 409 in ServiceCatalogService.';
