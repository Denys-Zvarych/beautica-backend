-- Phase 19.1 — Favorites schema + endpoints.
--
-- A CLIENT can favorite an *independent master* (a users row with role
-- INDEPENDENT_MASTER that owns a masters row) or a salon, and read those lists
-- back for the "Улюблені майстри" screen and the like-buttons on search-result
-- and public-profile cards.
--
-- Design decisions (locked in the phase doc):
--   • Polymorphic, NOT two tables. One `favorites` table with a `target_type`
--     discriminator keeps the like/unlike endpoint surface uniform. target_type
--     is constrained by a DB CHECK to ('MASTER','SALON'). 'MASTER' here means an
--     INDEPENDENT_MASTER's masters.id — the application layer (not the CHECK)
--     rejects a SALON_MASTER target (404/400), since Postgres CHECK cannot
--     reference another table.
--   • NO foreign key on target_id. It is polymorphic (points at masters.id OR
--     salons.id depending on target_type), so a single FK is impossible.
--     Target existence + favoritability is validated in FavoriteService (404 on
--     a missing/invalid target). Orphan-row risk on target deletion is bounded:
--     a stale favorite simply yields nothing in the read projection's inner JOIN.
--   • Idempotent POST = 200. The UNIQUE (client_id, target_type, target_id)
--     backs the idempotent insert — a duplicate tuple is a no-op that returns
--     the existing row, never a 409 (FavoriteService pre-checks + the unique
--     index is the last-resort guard).
--   • client_id FK to users with ON DELETE CASCADE — deleting an account drops
--     its favorites automatically; no orphan blob risk (no external storage
--     reference on this table). AbstractIntegrationTest.cleanDb() still DELETEs
--     `favorites` explicitly before `users` (§O-7), not relying on CASCADE.
--
-- Idempotent DDL (IF NOT EXISTS) so a partial / re-run apply is safe.

CREATE TABLE IF NOT EXISTS favorites (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id   UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    target_type VARCHAR(16) NOT NULL,
    target_id   UUID NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_favorite_target_type CHECK (target_type IN ('MASTER', 'SALON')),
    CONSTRAINT uq_favorite UNIQUE (client_id, target_type, target_id)
);

-- Backs the per-client read endpoints (GET /favorites/masters, /favorites/salons):
-- both filter WHERE client_id = :clientId AND target_type = :type. The composite
-- (client_id, target_type) is also the leading prefix of the uq_favorite UNIQUE
-- index, but a dedicated index keeps the list scan SARGable without depending on
-- the UNIQUE constraint's column order.
CREATE INDEX IF NOT EXISTS idx_favorites_client ON favorites (client_id, target_type);
