-- Backs the creation-order sort of findByMasterIdAndIsActiveTrueWithGraph
-- (GET /masters/{masterId}/services). The query filters on
-- (master_id, is_active = TRUE) and now orders by created_at ASC, so a
-- partial composite index lets Postgres satisfy both the predicate and the
-- ORDER BY from the index without a sort step. Mirrors the always-active
-- predicate as a partial index so inactive rows do not bloat it.
CREATE INDEX IF NOT EXISTS idx_master_services_master_active_created
    ON master_services (master_id, is_active, created_at)
    WHERE is_active = TRUE;
