-- Phase — SEARCH contract: name / service-name free-text search (?q=).
--
-- The master and salon discovery queries match the supplied term with a
-- case-insensitive containment predicate:
--     users.first_name        ILIKE '%term%'
--     users.last_name         ILIKE '%term%'
--     service_definitions.name ILIKE '%term%'
--     salons.name             ILIKE '%term%'
--
-- A plain B-tree cannot serve a leading-wildcard ILIKE. GIN trigram indexes
-- (pg_trgm, already enabled in V12) make these containment scans index-served
-- at scale instead of degrading into a full sequential scan once the tables
-- grow. The indexes are advisory for correctness (the queries are correct
-- without them) but required for performance headroom.
--
-- The free-text predicates only ever run under `is_active = true` (the master
-- and salon discovery SQL both carry that constant filter, and the service-name
-- match is scoped to active service_definitions). Each GIN trigram index is
-- therefore PARTIAL `WHERE is_active = true`: soft-deleted rows never enter the
-- index (cheaper writes, smaller working set) and the planner can serve the
-- ILIKE without an is_active recheck. Verified every base table has an
-- is_active column — users (V1), service_definitions (V6), salons (V3) all do —
-- so all four indexes are partial. pg_trgm already skips NULLs, so the nullable
-- users.first_name / users.last_name columns are unaffected.
--
-- Additive + idempotent: CREATE EXTENSION / CREATE INDEX both use IF NOT
-- EXISTS, so a clean-DB replay yields an identical result and re-running is a
-- no-op. No data is written. (Note: CONCURRENTLY is intentionally NOT used —
-- Flyway runs each migration inside a transaction, and CREATE INDEX
-- CONCURRENTLY cannot run in a transaction block. On the current table sizes a
-- brief ACCESS EXCLUSIVE lock is acceptable; revisit with a manual
-- CONCURRENTLY build if these tables grow large before launch.)

CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_users_first_name_trgm
    ON users USING gin (first_name gin_trgm_ops)
    WHERE is_active = true;

CREATE INDEX IF NOT EXISTS idx_users_last_name_trgm
    ON users USING gin (last_name gin_trgm_ops)
    WHERE is_active = true;

CREATE INDEX IF NOT EXISTS idx_service_definitions_name_trgm
    ON service_definitions USING gin (name gin_trgm_ops)
    WHERE is_active = true;

CREATE INDEX IF NOT EXISTS idx_salons_name_trgm
    ON salons USING gin (name gin_trgm_ops)
    WHERE is_active = true;
