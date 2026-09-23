-- V170: widen `cities` into the full settlement taxonomy (Phase 325, schema half)
--
-- Phase 325 drops the «Область» picker in favour of a single «Населений пункт»
-- field, so the table behind that field must hold EVERY settlement — villages
-- included — not just the 356 category-M cities V53 seeded. This migration does
-- the schema and the corrective data work; V171 (Java) loads the 25 697-row
-- free-settlement CSV.
--
-- REUSE, NOT A PARALLEL TABLE (phase-325 D2): `cities` already IS a settlements
-- table with a thin seed, and users.city_id / salons.city_id / city_districts
-- already FK it. A second `settlements` table would drift from the taxonomy the
-- address FKs point at. `oblast_id` STAYS (D1): the product removes the oblast
-- INPUT, not the oblast DATA — it is what disambiguates «Іванівка, Полтавська
-- область», and 99 free «Іванівка» rows exist.
--
-- THERE IS DELIBERATELY NO `occupation_status` COLUMN (phase-325 D3). Occupied
-- settlements are never imported: Phase 324's exclusion set is applied to the
-- import SOURCE, not stored as a flag. No occupied row ever reaches this table,
-- so the occupied-territory ban is satisfied structurally rather than by a WHERE
-- clause a future query can omit.
--
-- ============================================================================
-- SECTION 1 — the 17 occupied cities V53 shipped, and why they are here
-- ============================================================================
-- V53's territory filter ran at OBLAST level and excluded exactly four codes:
-- Crimea, Donetsk oblast, Luhansk oblast and Sevastopol. Запорізька and
-- Херсонська oblasts were kept WHOLESALE, so their occupied cities were seeded
-- and are in every database built from this chain today:
--
--   Бердянськ, Приморськ, Василівка, Дніпрорудне, Енергодар,
--   Кам'янка-Дніпровська, Мелітополь, Молочанськ, Пологи, Токмак   (Запорізька)
--   Генічеськ, Каховка, Нова Каховка, Таврійськ, Гола Пристань,
--   Скадовськ, Олешки                                              (Херсонська)
--
-- Every one of the 17 is in Phase 324's exclusion set with an open-ended
-- «Тимчасово окупована» status. They are deleted here. Symmetrically, V53's
-- oblast-level scrub removed the FREE Донецька/Луганська settlements
-- (Краматорськ, Слов'янськ, м. Лиман) that V171 restores.
--
-- FK fan-out, handled explicitly rather than left to chance:
--   * salons.city_id is NOT NULL (V150/V151), so a referencing salon CANNOT be
--     repointed silently. This migration ABORTS with a named error, following
--     V150's own precedent: a human decides what happens to that salon. On any
--     clean database — Testcontainers, CI, a freshly built local DB — the count
--     is zero and this is a no-op guard.
--   * salons.district_id (V54, NULLABLE) is guarded the SAME way and in the same
--     DO block, not left to the raw FK error `DELETE FROM city_districts` would
--     otherwise raise. It is unreachable today — a salon's district is a child of
--     its city, so the city guard above already fires first — but "unreachable"
--     is an invariant of TODAY's data, and an asymmetric pair of guards is how the
--     two paths diverge later: one aborts with an actionable sentence, the other
--     with `violates foreign key constraint fk_salons_district_id`. Both now name
--     the cause and both tell the operator what to do.
--   * users.city_id / users.district_id are NULLABLE by design and the app
--     already renders a null city as "not set", so they are cleared. The user
--     re-picks; nothing is destroyed but a reference we may not serve.

-- ---------------------------------------------------------------------------
-- 1a. Abort if a salon still stands on one of the 17 — by city OR by district.
--     Both salon FKs are checked here so neither path can reach a raw FK error.
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    blocking_cities    BIGINT;
    blocking_districts BIGINT;
BEGIN
    SELECT COUNT(*) INTO blocking_cities
    FROM salons s
    JOIN cities c ON c.id = s.city_id
    WHERE c.katotth_code IN (
        'UA23020050010019935', 'UA23020130010076068', 'UA23040030010016724',
        'UA23040090010050034', 'UA23040110010044100', 'UA23040130010014334',
        'UA23080070010092407', 'UA23100150010091297', 'UA23100190010032690',
        'UA23100270010029314', 'UA65040010010040633', 'UA65060110010021041',
        'UA65060170010075325', 'UA65060250010044738', 'UA65080030010035864',
        'UA65080150010023642', 'UA65100110010019482'
    );

    IF blocking_cities > 0 THEN
        RAISE EXCEPTION
            'V170 blocked: % salon row(s) reference a city that V53 seeded in error and '
            'that Phase 324 lists as currently occupied. salons.city_id is NOT NULL, so '
            'these rows cannot be repointed automatically. Resolve each by hand (move the '
            'salon to a serviced city, or delete it) before this migration can apply.',
            blocking_cities;
    END IF;

    -- Symmetric guard for the second salon FK. `DELETE FROM city_districts` in 1b
    -- would otherwise abort on fk_salons_district_id with a bare Postgres FK
    -- message that names neither this migration nor the remedy.
    SELECT COUNT(*) INTO blocking_districts
    FROM salons s
    JOIN city_districts d ON d.id = s.district_id
    JOIN cities c ON c.id = d.city_id
    WHERE c.katotth_code IN (
        'UA23020050010019935', 'UA23020130010076068', 'UA23040030010016724',
        'UA23040090010050034', 'UA23040110010044100', 'UA23040130010014334',
        'UA23080070010092407', 'UA23100150010091297', 'UA23100190010032690',
        'UA23100270010029314', 'UA65040010010040633', 'UA65060110010021041',
        'UA65060170010075325', 'UA65060250010044738', 'UA65080030010035864',
        'UA65080150010023642', 'UA65100110010019482'
    );

    IF blocking_districts > 0 THEN
        RAISE EXCEPTION
            'V170 blocked: % salon row(s) reference an urban district of a city that V53 '
            'seeded in error and that Phase 324 lists as currently occupied. Those district '
            'rows are deleted by this migration, so the references must be resolved by hand '
            '(move the salon to a serviced locality, or delete it) first. Reaching this '
            'branch also means a salon''s district and city disagree, since the city guard '
            'above did not fire — repair that inconsistency too.',
            blocking_districts;
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- 1b. Release the nullable user references, then delete.
--     The district clear is scoped through city_districts so a user whose
--     district hangs off a doomed city loses both halves together, never one.
-- ---------------------------------------------------------------------------
-- A plain (non-temporary) staging table, dropped at the end of this section.
-- A TEMPORARY table would be scoped to Flyway's session rather than to this
-- migration, so a mid-migration failure would leave it behind and make the
-- retry fail on CREATE instead of on the real problem.
DROP TABLE IF EXISTS v170_occupied_city_ids;

CREATE TABLE v170_occupied_city_ids AS
SELECT id FROM cities WHERE katotth_code IN (
    'UA23020050010019935', 'UA23020130010076068', 'UA23040030010016724',
    'UA23040090010050034', 'UA23040110010044100', 'UA23040130010014334',
    'UA23080070010092407', 'UA23100150010091297', 'UA23100190010032690',
    'UA23100270010029314', 'UA65040010010040633', 'UA65060110010021041',
    'UA65060170010075325', 'UA65060250010044738', 'UA65080030010035864',
    'UA65080150010023642', 'UA65100110010019482'
);

UPDATE users
SET district_id = NULL
WHERE district_id IN (
    SELECT d.id FROM city_districts d
    WHERE d.city_id IN (SELECT id FROM v170_occupied_city_ids)
);

UPDATE users
SET city_id = NULL
WHERE city_id IN (SELECT id FROM v170_occupied_city_ids);

DELETE FROM city_districts
WHERE city_id IN (SELECT id FROM v170_occupied_city_ids);

DELETE FROM cities
WHERE id IN (SELECT id FROM v170_occupied_city_ids);

DROP TABLE v170_occupied_city_ids;

-- ============================================================================
-- SECTION 2 — the two oblasts V53's oblast-level scrub removed entirely
-- ============================================================================
-- Донецька and Луганська hold 370 and 13 FREE settlements respectively. Their
-- oblast rows must exist before V171 can resolve those settlements' oblast_id.
-- Crimea (UA01) and Sevastopol (UA85) are NOT added: D1 excludes them wholesale.
INSERT INTO oblasts (katotth_code, name_uk, name_en)
VALUES ('UA14000000000091971', 'Донецька', 'Donetska')
ON CONFLICT (katotth_code) DO NOTHING;

INSERT INTO oblasts (katotth_code, name_uk, name_en)
VALUES ('UA44000000000018893', 'Луганська', 'Luhanska')
ON CONFLICT (katotth_code) DO NOTHING;

-- ============================================================================
-- SECTION 3 — the two new columns
-- ============================================================================
-- settlement_type mirrors the KATOTTH category letter:
--   M -> CITY   T -> TOWN (смт)   C -> VILLAGE (село)   X -> SETTLEMENT (селище)
--
-- TOWN is a legal value that the 2025-07-02 classifier never produces: the
-- селище-міського-типу category was retired and those places now carry X. It is
-- kept in the CHECK so a later classifier version that reintroduces T imports
-- without a schema change; today it is simply an empty bucket.
--
-- DEFAULT 'CITY' backfills the rows already in the table correctly and by fact,
-- not by convenience: after Section 1 every surviving row is one of V53's
-- category-M cities plus Kyiv, all of which ARE cities. The default is dropped
-- immediately afterwards so V171 and every future insert must state the type.
ALTER TABLE cities
    ADD COLUMN settlement_type VARCHAR(20) NOT NULL DEFAULT 'CITY';

ALTER TABLE cities
    ADD CONSTRAINT chk_cities_settlement_type
        CHECK (settlement_type IN ('CITY', 'TOWN', 'VILLAGE', 'SETTLEMENT'));

ALTER TABLE cities
    ALTER COLUMN settlement_type DROP DEFAULT;

-- is_major drives the "biggest places first" list shown before the user types.
-- KATOTTH carries no population data, so this is curated by hand in
-- scripts/locality/build_settlement_import.py (the 23 serviceable oblast
-- centres + the 27 next-largest cities = 50). FALSE is the correct default for
-- the ~25 650 settlements that are not on that list.
ALTER TABLE cities
    ADD COLUMN is_major BOOLEAN NOT NULL DEFAULT FALSE;

-- ============================================================================
-- SECTION 4 — indexes
-- ============================================================================
-- Partial index mirroring the exact predicate of the pre-typing list
-- (`WHERE is_major ORDER BY name_uk`). A plain index on is_major would be 25 697
-- entries to find 50 rows; the partial index is 50 entries and is ordered, so the
-- query is an index-only scan with no sort. See the playbook §E5.
CREATE INDEX idx_cities_major_name_uk
    ON cities (name_uk)
    WHERE is_major;

-- NOTE FOR PHASE 326: there is deliberately NO text-search index here.
-- `cities` goes from 356 to 25 698 rows in this migration, so the settlement
-- autocomplete needs one — but the right shape (pg_trgm GIN for infix, a
-- text_pattern_ops B-tree for prefix-only) is determined by the query Phase 326
-- actually writes, and guessing it now would ship a wasted index and a second
-- migration to drop it. Phase 326 owns that choice.
