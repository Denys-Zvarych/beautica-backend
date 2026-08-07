-- Phase 31.3 — widen the favorites target_type CHECK to admit SERVICE.
--
-- V92 created chk_favorite_target_type as:
--     CHECK (target_type IN ('MASTER','SALON'))
--
-- The BEAUTY WISH LIST stores a favourited (master, service) pair; target_id is a
-- master_services.id — the same identity CreateBookingRequest.masterServiceId and
-- GET /masters/{id}/slots?serviceId= already bind, which is why a wish-listed row can
-- start a booking with no extra lookup. Without this widening Hibernate persists the new
-- enum value and the INSERT fails the CHECK at runtime, not at startup.
--
-- V92's uq_favorite UNIQUE (client_id, target_type, target_id) already admits the new
-- value unchanged and needs no migration. The existing idx_favorites_client
-- (client_id, target_type) already covers the new list query's predicate.
--
-- No FK on target_id, and none is added: the column is polymorphic (masters.id OR
-- salons.id OR master_services.id), so a single FK is structurally impossible — the same
-- reason V92 has none. A stale row whose target vanished is dropped by the list query's
-- INNER JOIN rather than by referential integrity.
--
-- Product rule (deliberate asymmetry, documented in FavoriteTargetType's javadoc):
-- a MASTER favourite is rejected for a salon-employed SALON_MASTER, but a SERVICE
-- favourite is NOT — a wish-listed service is a rebook shortcut, not an endorsement of a
-- person. That rule is application-layer either way; Postgres CHECK cannot reach users.role.
--
-- The shipped V92 constraint is NEVER edited (immutable migration); this fix-forward
-- migration drops and re-adds the constraint under the SAME name with the extra value.
-- Idempotent: DROP ... IF EXISTS makes a clean-DB replay deterministic. All prior values
-- are retained byte-for-byte.
ALTER TABLE favorites DROP CONSTRAINT IF EXISTS chk_favorite_target_type;

ALTER TABLE favorites
    ADD CONSTRAINT chk_favorite_target_type CHECK (
        target_type IN ('MASTER', 'SALON', 'SERVICE'));
