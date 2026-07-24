-- Composite partial index for DashboardService.resolveScope()
-- findTopByOwnerIdAndIsActiveTrueOrderByCreatedAtAsc was doing a table scan
-- on the salons table for every dashboard cache miss.
CREATE INDEX IF NOT EXISTS idx_salons_owner_active_created
    ON salons (owner_id, created_at ASC)
    WHERE is_active = true;
