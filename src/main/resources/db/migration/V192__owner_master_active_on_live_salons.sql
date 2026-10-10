-- Phase 346: the owner-as-master toggle (POST/DELETE /api/v1/salons/{salonId}/master) is removed.
-- The owner is always a master of their salon, so "owner of a live salon => active SALON_OWNER
-- master row" must hold on every existing database. This reactivates any stray inactive owner row
-- left behind by the removed DELETE endpoint.
--
-- Scope (deliberately narrow):
--   * UPDATE only — no INSERT. An owner with no row at all is legacy dev data; reseed instead.
--   * Live salons only. "Not deleted" is SalonRepository's predicate `s.isActive = true`
--     (salon deletion is a soft delete that also deactivates its masters — those rows stay as is).
--   * The salon must be owned by the row's own user (s.owner_id = m.user_id).
--   * The owner's account must be active (users.is_active = true): a disabled account's row is
--     not resurrected into the public catalogue/rating set.
--   * master_services and schedules are untouched: bookability alone decides client visibility.
--
-- Rating aggregate: the app path publishes SalonStaffChangedEvent, whose listener re-runs
-- ReviewRepository#recalculateSalonRating. A direct SQL reactivation publishes nothing, so step 3
-- recomputes salons.avg_rating / review_count for exactly the affected salons with that query's
-- SQL verbatim (only `:salonId` is bound to the affected-salon row via LATERAL). If that query changes,
-- this file does NOT need to follow — it is applied history — but the two were identical at V192.
--
-- Idempotent: the `m.is_active = false` guard makes a re-run select zero rows, so steps 2-3
-- touch nothing. Deterministic: no random values; a clean-DB replay yields the same result.
-- The temp table is session-scoped and dropped at the end (and by rollback on failure).
-- No cache eviction is needed: application caches are in-memory and empty when Flyway runs at boot.

-- 1. Capture the rows to reactivate (and their salons) before flipping them.
CREATE TEMPORARY TABLE v192_reactivated_owner_masters AS
SELECT m.id AS master_id, m.salon_id
FROM masters m
WHERE m.master_type = 'SALON_OWNER'
  AND m.is_active = false
  AND EXISTS (SELECT 1
              FROM salons s
              WHERE s.id = m.salon_id
                AND s.owner_id = m.user_id
                AND s.is_active = true)
  AND EXISTS (SELECT 1
              FROM users u
              WHERE u.id = m.user_id
                AND u.is_active = true);

-- 2. Reactivate.
UPDATE masters m
SET is_active  = true,
    updated_at = now()
WHERE m.id IN (SELECT r.master_id FROM v192_reactivated_owner_masters r);

-- 3. Recompute the salon rating aggregate. The LATERAL body is ReviewRepository
--    #recalculateSalonRating's FROM-subquery verbatim, with `:salonId` bound to a.salon_id.
UPDATE salons s
SET avg_rating   = agg.avg_rating,
    review_count = agg.cnt
FROM (SELECT DISTINCT salon_id FROM v192_reactivated_owner_masters) a
CROSS JOIN LATERAL (SELECT COALESCE(AVG(pm.master_avg), 0) AS avg_rating,
                           COALESCE(SUM(pm.master_cnt), 0) AS cnt
                      FROM (SELECT r.master_id,
                                   AVG(r.rating::numeric) AS master_avg,
                                   COUNT(*)               AS master_cnt
                              FROM reviews r
                              JOIN masters m ON m.id = r.master_id
                             WHERE r.salon_id = a.salon_id
                               AND m.salon_id = a.salon_id
                               AND m.is_active = true
                             GROUP BY r.master_id) pm) agg
WHERE s.id = a.salon_id;

DROP TABLE v192_reactivated_owner_masters;
