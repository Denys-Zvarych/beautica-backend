-- V178: finish the settlement-label backfill V177 started
--
-- V177 re-derived salons.city from cities.name_uk. Two sibling columns carry the
-- same stale-denormalisation defect and are repaired here. (V177 is already applied
-- on at least one database, so it is immutable; this is the fix-forward.)
--
--   1. salons.region — legacy free text, never re-derived since Phase 10.6.
--      SalonService now writes it from the settlement's oblast (oblasts.name_uk)
--      whenever it writes city_id.
--
--   2. users.city / users.region for SALON_OWNER rows. An owner's users.city_id is
--      written ONLY by SalonService#createSalon's owner sync (UserService's profile
--      locality write skips SALON_OWNER), and that sync copied the FK without the
--      labels — so /users/me showed the owner's PREVIOUS city/region text (or NULL)
--      next to the salon's city_id. The sync now applies the same
--      User#applySettlementDisplayNames that UserService uses. Other roles are left
--      alone: their city_id is written only by UserService, which has always
--      written the labels with it.
--
-- Set-based and idempotent: one UPDATE ... FROM per table, each guarded by
-- IS DISTINCT FROM so a re-run touches zero rows and already-correct rows are
-- never rewritten. salons.city is re-asserted too, so this script alone yields
-- the final state regardless of V177. cities.oblast_id is NOT NULL (V52), so
-- every city has exactly one oblast. oblasts.name_uk fits the VARCHAR(100)
-- region columns. updated_at is deliberately NOT bumped: this is a derived-label
-- repair, not an owner edit.

UPDATE salons s
   SET city   = c.name_uk,
       region = o.name_uk
  FROM cities c
  JOIN oblasts o ON o.id = c.oblast_id
 WHERE c.id = s.city_id
   AND (s.city IS DISTINCT FROM c.name_uk OR s.region IS DISTINCT FROM o.name_uk);

UPDATE users u
   SET city   = c.name_uk,
       region = o.name_uk
  FROM cities c
  JOIN oblasts o ON o.id = c.oblast_id
 WHERE c.id = u.city_id
   AND u.role = 'SALON_OWNER'
   AND (u.city IS DISTINCT FROM c.name_uk OR u.region IS DISTINCT FROM o.name_uk);
