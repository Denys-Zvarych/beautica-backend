-- V179: clear the occupied-settlement TEXT that V170 left behind (fix-forward)
--
-- V170 §1b deleted the 17 occupied cities V53 seeded in error and NULLed every
-- users.city_id that pointed at them — but it left the denormalised
-- users.city / users.region labels in place. MasterDetailResponse serves
-- users.city publicly, so an INDEPENDENT_MASTER who had picked «Мелітополь»
-- still advertised it. Under the occupied-territory data ban that text must go.
--
-- ============================================================================
-- Scope — a SET test, never a blanket or prefix test
-- ============================================================================
-- NOT `WHERE city_id IS NULL`: a CLIENT's locality is optional, and legacy text
-- with no taxonomy id is legitimate data. A row is cleared only when ALL hold:
--   * city_id IS NULL                      (V170 released it, or it never had one)
--   * city is EXACTLY one of V170's 17 names
--   * region is NULL or names THAT city's oblast (with or without « область»)
-- The region clause is what keeps namesakes safe: «Василівка» and «Пологи» are
-- also villages in serviced oblasts, and a row whose region names a different
-- oblast is left alone. Донецька/Луганська are filtered per settlement (phase-324),
-- so Краматорськ / Слов'янськ / Лиман are never matched — they are not in the set.
--
-- The 17 (katotth_code, name_uk, oblast name_uk) triples below are DERIVED, not
-- typed: the codes are V170's `v170_occupied_city_ids` list verbatim, and each
-- name/oblast is the row V53 inserted for that code. V179ClearOccupiedCityTextMigrationTest
-- re-derives both from the V53 and V170 scripts on the classpath and fails if this
-- list drifts from them by a single row or character.
--
-- Region-only rows: AR Crimea and Sevastopol are excluded wholesale (phase-324
-- D1). No oblasts row ever existed for them, and since V54 users.region is only
-- written from oblasts.name_uk, so such text can only be pre-V54 legacy free
-- text. It is cleared together with its city — a Crimean city label is occupied
-- data regardless of which settlement it names.
--
-- Salons need no clause: salons.city_id is NOT NULL, V170 aborts if any salon
-- stood on one of the 17, and V178 re-derived every salons.city/region from the
-- taxonomy — so no salon row can carry these labels.
--
-- Idempotent: a cleared row no longer matches (city IS NULL), so a re-run
-- updates zero rows. updated_at is deliberately NOT bumped.

WITH v170_purged (katotth_code, city_uk, oblast_uk) AS (
    VALUES
    ('UA23020050010019935', 'Бердянськ', 'Запорізька'),
    ('UA23020130010076068', 'Приморськ', 'Запорізька'),
    ('UA23040030010016724', 'Василівка', 'Запорізька'),
    ('UA23040090010050034', 'Дніпрорудне', 'Запорізька'),
    ('UA23040110010044100', 'Енергодар', 'Запорізька'),
    ('UA23040130010014334', 'Кам’янка-Дніпровська', 'Запорізька'),
    ('UA23080070010092407', 'Мелітополь', 'Запорізька'),
    ('UA23100150010091297', 'Молочанськ', 'Запорізька'),
    ('UA23100190010032690', 'Пологи', 'Запорізька'),
    ('UA23100270010029314', 'Токмак', 'Запорізька'),
    ('UA65040010010040633', 'Генічеськ', 'Херсонська'),
    ('UA65060110010021041', 'Каховка', 'Херсонська'),
    ('UA65060170010075325', 'Нова Каховка', 'Херсонська'),
    ('UA65060250010044738', 'Таврійськ', 'Херсонська'),
    ('UA65080030010035864', 'Гола Пристань', 'Херсонська'),
    ('UA65080150010023642', 'Скадовськ', 'Херсонська'),
    ('UA65100110010019482', 'Олешки', 'Херсонська')
)
UPDATE users u
   SET city = NULL,
       region = NULL
  FROM v170_purged p
 WHERE u.city_id IS NULL
   AND u.city = p.city_uk
   AND (u.region IS NULL OR u.region IN (p.oblast_uk, p.oblast_uk || ' область'));

UPDATE users
   SET city = NULL,
       region = NULL
 WHERE city_id IS NULL
   AND region IN ('Автономна Республіка Крим', 'АР Крим', 'Крим',
                  'Севастополь', 'м. Севастополь');
