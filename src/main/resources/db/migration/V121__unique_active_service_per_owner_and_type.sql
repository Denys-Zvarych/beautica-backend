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

-- Step 0 — AUDIT TRAIL for step 1, written BEFORE anything is destroyed.
--
-- Step 1 below soft-deactivates rows a provider created and may still be actively using.
-- Deactivation is silent and, without a record of WHICH rows were picked, unrecoverable:
-- the surviving row's id is not derivable after the fact, so support could neither explain
-- nor undo "my price disappeared". This table IS the record. It is deliberately a permanent
-- table (not TEMP): a TEMP table dies with the Flyway session, taking the evidence with it.
--
-- Survivor selection (the ORDER BY inside ranked) prefers a row that carries BOOKING HISTORY
-- over one that does not. Bookings reference master_services.id, which references
-- service_def_id — so a definition with bookings is the one a real client actually transacted
-- against, and deactivating it is strictly worse than deactivating an untouched twin.
-- Only when the booking signal ties do the original recency tie-breaks apply
-- (updated_at, created_at, id — the last purely for determinism when seeded fixtures share
-- timestamps).
--
-- NOT registered in AbstractIntegrationTest.cleanDb (anti-bug §O-7) on purpose: no application
-- code ever writes here, and on a fresh test database step 1 finds nothing, so the table is
-- always empty in tests. Deleting from it would be deleting production forensics.
CREATE TABLE v121_dedup_log AS
WITH ranked AS (
    SELECT sd.id,
           sd.owner_type,
           sd.owner_id,
           sd.service_type_id,
           ROW_NUMBER() OVER (
               PARTITION BY sd.owner_type, sd.owner_id, sd.service_type_id
               ORDER BY (EXISTS (SELECT 1
                                 FROM master_services ms
                                          JOIN bookings b ON b.master_service_id = ms.id
                                 WHERE ms.service_def_id = sd.id)) DESC,
                        sd.updated_at DESC,
                        sd.created_at DESC,
                        sd.id DESC
           ) AS rn
    FROM service_definitions sd
    WHERE sd.is_active = true
)
SELECT id,
       owner_type,
       owner_id,
       service_type_id,
       now() AS deactivated_at
FROM ranked
WHERE rn > 1;

COMMENT ON TABLE v121_dedup_log IS
    'Forensic record of the ServiceDefinition rows V121 soft-deactivated to satisfy ux_service_def_owner_service_type_active. Written before the UPDATE; never touched by application code. Survivor was chosen preferring rows with booking history, then updated_at/created_at/id DESC.';

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
