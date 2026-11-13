-- V235 — repair SALON_OWNERs left without a usable owner-as-master row after deleting their only
-- salon and creating a new one (hotfix fix/owner-recreate-salon-master; SAL-04 "owner is always a
-- master of their salon").
--
-- THE BUG (fixed in code by the same change): SalonService#createSalon decided "first salon" with
-- existsByOwnerId, which counts DELETED (is_active = false) salons. So the new salon was created
-- with is_primary = false and createMasterForOwner never ran: the owner's SALON_OWNER master row
-- stayed inactive and still pointed at the deleted salon -> GET /masters/me 404. The deleted salon
-- also kept is_primary = true.
--
-- THIS MIGRATION, for every owner whose SALON_OWNER master row is inactive OR points at an inactive
-- salon, AND who owns at least one ACTIVE salon (owners with no active salon are left untouched):
--   1. clears is_primary on the owner's inactive salons (idx_salons_owner_primary, V56, is not
--      filtered on is_active — this must precede step 2);
--   2. if the owner has no active primary salon, marks their oldest active salon primary
--      (created_at, then id — deterministic);
--   3. re-homes the master row and reactivates it — exactly the columns
--      MasterService#createMasterForOwner's reactivation branch writes (is_active, salon_id; the
--      AuditableEntity listener bumps updated_at). Target salon: the row's own salon when that salon
--      is still active (plain reactivation, as V192), else the owner's active primary salon.
--      masters.user_id stays UNIQUE: the row is updated in place, never inserted.
--   4. recomputes the rating aggregate of every target salon, as the app path's
--      SalonStaffChangedEvent listener does — the exact predicate set of ReviewRepository
--      #recalculateSalonRating, including moderation_status = 'VISIBLE' (a hidden/pending review
--      must never re-enter the public avg_rating/review_count).
--
-- Shapes covered: (i) only salon deleted, new one created (row inactive on the deleted salon, the
-- new salon not primary); (ii) primary salon A deleted while another salon B was active, then C
-- created (row inactive on A, no active salon primary) — steps 1-3 make the oldest active salon
-- primary and re-home the row onto it; (iii) row inactive on a still-active salon — reactivated in
-- place.
--
-- Same account guard as V192: a disabled owner account (users.is_active = false) is not
-- resurrected into the public catalogue.
-- Step 2 also copies the newly-primary salon's address onto the owner's users row (app rule).
--
-- Idempotent: after one pass every selected row is active on an active salon, so a re-run selects
-- zero owners and steps 1-4 touch nothing. Deterministic: no random values.
-- No cache eviction is needed: application caches are in-memory and empty when Flyway runs at boot.
-- Immutable-migration rule (Anti-Bug §O-9): new fix-forward version (max existing = V234).

-- ── Lock + statement guards ─────────────────────────────────────────────────────────────
-- Row-level UPDATEs on salons/masters only (ROW EXCLUSIVE + row locks on the affected owners'
-- rows). SET LOCAL is scoped to Flyway's per-migration transaction, so neither value leaks to the
-- pooled connection. Same values as V138.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '1min';

-- 0. Capture the stuck owner-master rows into a session-local temp table. No drop statement: the
--    expand/contract CI gate flags every DROP keyword (ON COMMIT DROP included), and ON COMMIT
--    DROP / DELETE ROWS would empty the table after each statement under autocommit (the IT
--    re-applies this script that way). Instead: IF NOT EXISTS + DELETE make a re-run on the same
--    session safe, and the trailing DELETE leaves only an empty definition that ends with the
--    session (flyway-pool keeps no idle connections).
CREATE TEMPORARY TABLE IF NOT EXISTS v235_stuck_owner_masters (
    master_id     UUID,
    owner_id      UUID,
    keep_salon_id UUID
);
DELETE FROM v235_stuck_owner_masters;
INSERT INTO v235_stuck_owner_masters (master_id, owner_id, keep_salon_id)
SELECT m.id      AS master_id,
       m.user_id AS owner_id,
       CASE WHEN own.is_active THEN m.salon_id END AS keep_salon_id
FROM masters m
JOIN users u ON u.id = m.user_id
LEFT JOIN salons own ON own.id = m.salon_id
WHERE m.master_type = 'SALON_OWNER'
  AND u.role = 'SALON_OWNER'
  AND u.is_active = true
  AND (m.is_active = false OR own.id IS NULL OR own.is_active = false)
  AND EXISTS (SELECT 1 FROM salons s WHERE s.owner_id = m.user_id AND s.is_active = true);

-- 1. Release is_primary held by the owners' deleted salons.
UPDATE salons s
SET is_primary = false
WHERE s.is_primary = true
  AND s.is_active = false
  AND s.owner_id IN (SELECT t.owner_id FROM v235_stuck_owner_masters t);

-- 2. Owners with no active primary salon: the oldest active salon becomes primary, and the owner's
--    users-row address is copied from it — the app rule (SalonService#copyPrimaryAddressOntoOwner):
--    /users/me mirrors the salon that BECOMES primary. Plain column copy: the salon's city/region are
--    the same SettlementDisplayNameResolver labels the app writes onto the owner (both rows are
--    denormalised from one resolve). Owners whose active primary already existed are not touched.
WITH promoted AS (
    UPDATE salons s
    SET is_primary = true
    FROM (SELECT DISTINCT ON (a.owner_id) a.id
          FROM salons a
          WHERE a.is_active = true
            AND a.owner_id IN (SELECT t.owner_id FROM v235_stuck_owner_masters t)
            AND NOT EXISTS (SELECT 1 FROM salons p
                            WHERE p.owner_id = a.owner_id AND p.is_active = true AND p.is_primary = true)
          ORDER BY a.owner_id, a.created_at, a.id) oldest
    WHERE s.id = oldest.id
    RETURNING s.owner_id, s.city_id, s.district_id, s.city, s.region, s.street, s.building_no, s.location_note
)
UPDATE users u
SET city_id       = pr.city_id,
    district_id   = pr.district_id,
    city          = pr.city,
    region        = pr.region,
    street        = pr.street,
    building_no   = pr.building_no,
    location_note = pr.location_note,
    updated_at    = now()
FROM promoted pr
WHERE u.id = pr.owner_id;

-- 3. Re-home (when the row's salon is gone) and reactivate.
UPDATE masters m
SET salon_id   = COALESCE(t.keep_salon_id,
                          (SELECT p.id FROM salons p
                           WHERE p.owner_id = t.owner_id AND p.is_active = true AND p.is_primary = true)),
    is_active  = true,
    updated_at = now()
FROM v235_stuck_owner_masters t
WHERE m.id = t.master_id;

-- 4. Recompute the rating aggregate of every salon a row landed on.
UPDATE salons s
SET avg_rating   = agg.avg_rating,
    review_count = agg.cnt
FROM (SELECT DISTINCT m.salon_id
      FROM masters m
      JOIN v235_stuck_owner_masters t ON t.master_id = m.id) a
CROSS JOIN LATERAL (SELECT COALESCE(AVG(pm.master_avg), 0) AS avg_rating,
                           COALESCE(SUM(pm.master_cnt), 0) AS cnt
                      FROM (SELECT r.master_id,
                                   AVG(r.rating::numeric) AS master_avg,
                                   COUNT(*)               AS master_cnt
                              FROM reviews r
                              JOIN masters m ON m.id = r.master_id
                             WHERE r.salon_id = a.salon_id
                               AND r.moderation_status = 'VISIBLE'
                               AND m.salon_id = a.salon_id
                               AND m.is_active = true
                             GROUP BY r.master_id) pm) agg
WHERE s.id = a.salon_id;

DELETE FROM v235_stuck_owner_masters;
