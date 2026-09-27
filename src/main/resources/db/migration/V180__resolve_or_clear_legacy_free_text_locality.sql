-- V180: resolve pre-V54 free-text user locality against the SERVICED settlement set,
--       and clear what cannot be resolved where it would be shown publicly
--
-- Before V54 (Phase 10.3) users.city / users.region were free text. Since then
-- they are only ever written from the taxonomy (cities.name_uk / oblasts.name_uk),
-- but legacy rows survive with city_id NULL. V179 cleared the 17 cities V170
-- deleted; a legacy row naming ANY other occupied settlement (e.g. «Донецьк»,
-- which no migration ever seeded) is still served by MasterDetailResponse for a
-- provider. A names list cannot close that: the exclusion set is committed as
-- codes only, by policy. The SERVICED set can — `cities` holds every free
-- settlement (V171) and nothing occupied (V170 + phase-324), so "resolvable in
-- cities" IS "serviced".
--
-- ============================================================================
-- Step 1 — re-run V97's backfill against today's cities table (all roles)
-- ============================================================================
-- V97 ran when `cities` held 356 category-M rows. V171 widened it to ~25 700
-- settlements, so rows V97 had to skip may resolve now. Same rule as V97:
-- (city, region) must name EXACTLY ONE cities row by name_uk within the oblast
-- named by region; ambiguous or unmatched rows are skipped, never guessed;
-- district_id is untouched. Two deliberate widenings, both lossless:
--   * apostrophes are normalised to the classifier's U+2019 before comparing —
--     a hand-typed «Слов'янськ» (ASCII ') or «Словʼянськ» (U+02BC) is the SAME
--     serviced city as the taxonomy's «Слов’янськ», and must not fall through to
--     the clearing steps below;
--   * region may carry the « область» suffix, as legacy free text often did.
-- A resolved row also gets its labels re-derived from the taxonomy (the V178 /
-- SettlementDisplayNameResolver rule: city/region always mirror city_id).
--
-- Occupied-namesake guard. In the seven PARTLY occupied oblasts (the step-3
-- KATOTTH list below) the occupied settlement itself is absent from `cities`,
-- so a legacy «X» naming an OCCUPIED X that has exactly one FREE namesake
-- village there would otherwise be bound to that village — misplacing the
-- user. There, the unique match must also be a CITY; a village/settlement match
-- is left unbound (a provider is then cleared by step 2, a CLIENT keeps its
-- private text under step 3 because the text still names a serviced place).
-- Uniqueness is still counted over ALL namesakes first, so a CITY that shares
-- its name with a village stays ambiguous and unbound, exactly as in V97.
UPDATE users u
   SET city_id = m.city_id,
       city    = m.city_uk,
       region  = m.oblast_uk
  FROM (
        SELECT u2.id                     AS user_id,
               (array_agg(c.id))[1]      AS city_id,
               (array_agg(c.name_uk))[1] AS city_uk,
               (array_agg(o.name_uk))[1] AS oblast_uk
          FROM users u2
          JOIN oblasts o ON u2.region IN (o.name_uk, o.name_uk || ' область')
          JOIN cities c  ON c.oblast_id = o.id
                        AND c.name_uk = translate(u2.city, '''ʼ', '’’')
         WHERE u2.city_id IS NULL
           AND u2.city IS NOT NULL
           AND u2.city <> ''
         GROUP BY u2.id
        HAVING count(*) = 1
           AND bool_and(c.settlement_type = 'CITY'
                        OR o.katotth_code NOT IN ('UA14000000000091971', 'UA23000000000064947',
                                                  'UA44000000000018893', 'UA48000000000039575',
                                                  'UA59000000000057109', 'UA63000000000041885',
                                                  'UA65000000000030969'))
       ) m
 WHERE u.id = m.user_id;

-- ============================================================================
-- Step 2 — providers: whatever is STILL unresolved is not shown publicly
-- ============================================================================
-- Provider roles' users.city is public (MasterDetailResponse). After step 1 a
-- NULL city_id beside non-empty text means the text names no single serviced
-- settlement in its oblast: occupied, garbage, an ambiguous namesake, or (in a
-- partly occupied oblast) a non-CITY namesake of a possibly occupied place.
-- None may be published; the provider re-picks from the taxonomy.
UPDATE users
   SET city = NULL,
       region = NULL
 WHERE city_id IS NULL
   AND role IN ('INDEPENDENT_MASTER', 'SALON_MASTER', 'SALON_OWNER', 'SALON_ADMIN')
   AND (city IS NOT NULL OR region IS NOT NULL);

-- ============================================================================
-- Step 3 — CLIENT: private, optional locality; clear only occupied territory
-- ============================================================================
-- A CLIENT's text is never served to anyone else and is legitimately free-form,
-- so an unresolved row is kept UNLESS its region places it in occupied territory:
--   * AR Crimea / Sevastopol — excluded wholesale (phase-324 D1); no oblasts row
--     ever existed for them, so these five spellings cannot be derived from data
--     and are the same set V179 uses;
--   * a PARTLY occupied oblast, AND the city names no serviced settlement of that
--     oblast. The seven oblasts are identified by KATOTTH code, not name: they are
--     exactly the oblasts whose two-digit prefix occurs in Phase 324's exclusion
--     set (UA14 UA23 UA44 UA48 UA59 UA63 UA65) —
--     V180LegacyFreeTextLocalityMigrationTest re-derives this list from the
--     committed code set and fails if they diverge. Краматорськ / Слов’янськ
--     resolve in step 1; Лиман (two serviced namesakes in Донецька, so step 1
--     skips it as ambiguous) still matches a serviced row here — none of them is
--     ever cleared for a CLIENT.
UPDATE users u
   SET city = NULL,
       region = NULL
 WHERE u.role = 'CLIENT'
   AND u.city_id IS NULL
   AND (
        u.region IN ('Автономна Республіка Крим', 'АР Крим', 'Крим',
                     'Севастополь', 'м. Севастополь')
     OR EXISTS (
            SELECT 1
              FROM oblasts o
             WHERE o.katotth_code IN ('UA14000000000091971', 'UA23000000000064947',
                                      'UA44000000000018893', 'UA48000000000039575',
                                      'UA59000000000057109', 'UA63000000000041885',
                                      'UA65000000000030969')
               AND u.region IN (o.name_uk, o.name_uk || ' область')
               AND NOT EXISTS (
                       SELECT 1 FROM cities c
                        WHERE c.oblast_id = o.id
                          AND c.name_uk = translate(u.city, '''ʼ', '’’'))
        )
   );
