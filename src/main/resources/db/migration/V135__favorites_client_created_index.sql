-- V135 — order-covering index for the three per-client favourites lists.
--
-- All three read paths (GET /favorites/masters, /favorites/salons, /favorites/services)
-- filter WHERE client_id = ? AND target_type = ? and then
-- ORDER BY created_at DESC, <target id> with a LIMIT/OFFSET page.
--
-- V92's idx_favorites_client (client_id, target_type) covers the predicate but NOT the
-- ordering, so Postgres had to sort the client's entire matching set before applying the
-- LIMIT. Appending created_at DESC lets the index supply the order directly, turning the
-- page read into an index scan + LIMIT. This matters more since Phase 31.4 added the
-- BEAUTY WISH LIST as a third list over the same table.
--
-- (client_id, target_type) is a left prefix of the new index, so idx_favorites_client
-- becomes redundant write amplification and is dropped in the same migration (§O-6).
-- uq_favorite (the UNIQUE on client_id, target_type, target_id) is a DIFFERENT index and
-- is deliberately left alone — it owns the idempotency constraint.
--
-- Idempotent both ways (IF NOT EXISTS / IF EXISTS) so a clean-DB replay is deterministic.

CREATE INDEX IF NOT EXISTS idx_favorites_client_created
    ON favorites (client_id, target_type, created_at DESC);

DROP INDEX IF EXISTS idx_favorites_client;
