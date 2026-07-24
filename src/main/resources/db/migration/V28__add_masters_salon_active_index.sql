-- Composite partial index to fully cover WHERE salon_id = ? AND is_active = TRUE predicates
-- used by getSalonMasters and getMastersByPage. Supersedes single-column idx_masters_salon_id
-- for active-only queries but does not drop it (full scans still need it).
CREATE INDEX IF NOT EXISTS idx_masters_salon_active
    ON masters(salon_id, is_active)
    WHERE is_active = TRUE;
