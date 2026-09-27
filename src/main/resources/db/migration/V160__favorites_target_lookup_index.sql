-- V160 — index supporting the Phase 268 D5 favourites bulk-delete predicate.
--
-- FavoriteRepository#deleteAllByTargetTypeAndTargetId (Phase 268 D5 — salon deactivation
-- cascade) issues:
--
--     DELETE FROM favorites WHERE target_type = ? AND target_id = ?
--
-- inside SalonService.deactivateSalon's @Transactional(timeout = 30). Neither existing
-- favorites index leads with (target_type, target_id):
--   • uq_favorite (client_id, target_type, target_id)              — V92, leads with client_id
--   • idx_favorites_client_created (client_id, target_type, created_at DESC) — V135, same
-- Both are keyed for the per-client read paths, not this cross-client bulk delete, so the
-- statement would Seq Scan the whole favorites table on every salon deactivation.
--
-- (target_type, target_id) is NOT a left prefix of either existing index, so nothing here
-- is made redundant and nothing is dropped in this migration (contrast V135, which dropped
-- idx_favorites_client because the new index subsumed it).
--
-- Idempotent (IF NOT EXISTS) so a clean-DB replay is deterministic.

CREATE INDEX IF NOT EXISTS idx_favorites_target
    ON favorites (target_type, target_id);
