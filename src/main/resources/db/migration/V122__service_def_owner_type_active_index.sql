-- Close the ServiceDefinition @Table(indexes = ...) drift (backlog MEDIUM).
--
-- ServiceDefinition.java declared TWO indices that existed in no migration:
--   idx_service_def_owner_active       (owner_id, is_active)
--   idx_service_def_owner_type_active  (owner_type, owner_id, is_active)
--
-- Only ONE of them was ever justified, so they are resolved in opposite directions:
--
--   * idx_service_def_owner_active (owner_id, is_active) is DELETED FROM THE ENTITY, not created
--     here. No query in the codebase filters service_definitions on owner_id WITHOUT also
--     constraining owner_type — owner_id is only meaningful when paired with the discriminator that
--     says which table it points into (SALON vs MASTER), so an owner_id-leading index has no caller
--     and never will. Creating it would have cost a write on every service insert/update to serve
--     nothing.
--
--   * idx_service_def_owner_type_active (owner_type, owner_id, is_active) IS created below: it has a
--     real, live production caller. ServiceRepository#findBookableServicesBySalon — the salon
--     public-profile bookable catalogue, reached from SearchService and ServiceCatalogService —
--     filters exactly `ownerType = SALON AND ownerId = :salonId AND isActive = true`. Until now that
--     fell back to idx_service_def_owner (owner_type, owner_id) plus a heap filter on is_active.
--
-- Deliberately NOT partial (`... WHERE is_active = true`), even though the justifying query always
-- filters is_active. A partial index cannot be expressed by JPA's @Index — that is precisely why
-- V121's ux_service_def_owner_service_type_active is absent from the entity (see ServiceDefinition's
-- class javadoc). Making this one partial would recreate the same annotation-cannot-mirror-schema
-- gap this migration exists to close. As a plain composite it both serves the is_active-filtered
-- catalogue query AND subsumes the unfiltered (owner_type, owner_id) lookups, which is what lets the
-- redundant index below be dropped.
--
-- Note this drift was silent and cosmetic, never a boot failure: Hibernate 6.5's
-- hbm2ddl.auto=validate does NOT check @Table(indexes = ...) against the real schema (empirically
-- verified in the Phase 26.8 audit — see V118's header).

-- CREATE INDEX (not CONCURRENTLY — Flyway wraps each migration in a single transaction, and
-- CREATE INDEX CONCURRENTLY cannot run inside one) takes SHARE on service_definitions: concurrent
-- reads proceed, concurrent writes block. service_definitions is a small, low-write catalogue table
-- (nothing like `bookings`), so the build is short. Bounded anyway on the same reasoning as V118/V119:
-- a pending lock request queues FIFO ahead of later writers, and a migration that outlives the
-- connection timeout crash-loops startup (memory `project_flyway_checksum_recovery.md`). Fail fast and
-- let the next deploy retry rather than stall a rolling deploy.
SET LOCAL lock_timeout = '5s';

CREATE INDEX IF NOT EXISTS idx_service_def_owner_type_active
    ON service_definitions (owner_type, owner_id, is_active);

-- idx_service_def_owner (owner_type, owner_id), from V6, is now a strict LEADING PREFIX of the index
-- created above. Postgres serves any (owner_type) or (owner_type, owner_id) lookup from the wider
-- btree, so every read the old index could satisfy is still satisfied — while keeping both would pay
-- two index writes per service insert/update for one index's worth of read benefit. Dropping it in
-- the same migration follows the same rule V118 applied to the retired price indices.
DROP INDEX IF EXISTS idx_service_def_owner;
