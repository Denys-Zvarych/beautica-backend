-- V97: one-time repair of users.city_id wiped by the writeLocalityFields FK-wipe bug.
--
-- ---------------------------------------------------------------------------
-- Bug being repaired
-- ---------------------------------------------------------------------------
-- UserService.writeLocalityFields used to assign user.setCityId(request.cityId())
-- UNCONDITIONALLY. A CLIENT location edit that PATCHes only street/locationNote
-- legitimately omits cityId (a CLIENT city is optional), so request.cityId() was
-- null — which wiped a previously-saved users.city_id FK to NULL while leaving the
-- denormalized users.city / users.region text columns intact. The mobile read path
-- resolves oblastId/cityId from city_id, so an affected client's location rendered
-- empty despite the city/region text still being present.
--
-- The write-path fix (only touch the FK + display strings when cityId is non-null)
-- ships alongside this migration. This migration self-heals rows already corrupted
-- before that fix landed.
--
-- ---------------------------------------------------------------------------
-- Conservative backfill — UNIQUE single-match only, never guess
-- ---------------------------------------------------------------------------
-- Re-derives city_id from the surviving denormalized (city, region) text by
-- joining to the taxonomy (cities + oblasts). A row is repaired ONLY when:
--   * city_id IS NULL (lost), AND
--   * users.city is non-null / non-empty, AND
--   * (city name AND oblast/region name) together resolve to EXACTLY ONE cities row.
-- If a (city, region) pair is ambiguous (the correlated subquery would return >1
-- cities row), the row is skipped and left NULL — we never guess. district_id is
-- left untouched (NULL): the denormalized text carries no district, and inferring
-- one is out of scope.
--
-- Occupied-territory oblasts are absent from the seeded taxonomy, so their rows
-- simply fail the join and are left NULL — no special-casing, no reintroduction.
--
-- Idempotent: the WHERE city_id IS NULL guard means a re-run (or a clean-DB replay
-- where no corruption exists) updates zero rows. It is a data backfill UPDATE, not
-- a schema change.
UPDATE users u
SET city_id = (
    SELECT c.id
    FROM cities c
    JOIN oblasts o ON o.id = c.oblast_id
    WHERE c.name_uk = u.city
      AND o.name_uk = u.region
)
WHERE u.city_id IS NULL
  AND u.city IS NOT NULL
  AND u.city <> ''
  AND (
      SELECT count(*)
      FROM cities c
      JOIN oblasts o ON o.id = c.oblast_id
      WHERE c.name_uk = u.city
        AND o.name_uk = u.region
  ) = 1;
