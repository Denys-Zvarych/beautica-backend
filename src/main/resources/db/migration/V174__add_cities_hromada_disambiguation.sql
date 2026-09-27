-- ============================================================================
-- V174 — hromada columns on `cities`, so a settlement label can disambiguate
-- ============================================================================
-- Phase 327, schema half. The data half is V175__backfill_settlement_hromadas,
-- a Java migration reading db/data/settlement_hromadas.csv.
--
-- THE DEFECT THIS CLOSES
--   Phase 326 labels every autocomplete row «‹назва›, ‹область›». Measured on
--   the 25 697 rows V171 imported, that disambiguates only 76.25 % of them:
--   2 234 name+oblast groups covering 6 103 rows are duplicated, worst
--   «Миколаївка, Харківська» x15. A master who picks the wrong village
--   publishes a wrong work address, and clients filter by locality, so an
--   unresolved pick is a silent correctness bug and not a cosmetic one.
--
--   Adding the hromada takes the residue to 111 groups / 226 rows (0.88 %).
--   The raion does NOT: it leaves 9.72 % broken for the same schema, CSV and
--   migration cost, and hromada is a SUBSET of raion, so raion can never
--   resolve what hromada cannot (phase-327 D1). Raion is not imported.
--
-- WHY `ambiguous_in_oblast` IS A STORED COLUMN AND NOT A QUERY (D2)
--   Ambiguity is a property of the IMPORTED SET, fixed the moment the CSV is
--   written — `cities` is Flyway-seed reference data with no runtime writer.
--   Recomputing it per keystroke would pay a self-join on a permitAll endpoint
--   to rediscover a constant. It is precomputed by
--   scripts/locality/build_settlement_import.py and loaded by V175.
--
--   It is also what keeps the label SHORT where it can be: 76 % of rows render
--   exactly as they do today, and only the colliding 24 % grow a third part.
--
-- WHY `hromada_name_uk` IS NULLABLE AND THE CHECK IS CONDITIONAL (D5)
--   Not every `cities` row has a hromada parent. Kyiv is KATOTTH category K —
--   an oblast-equivalent, not a level-4 settlement — and the two exclusion-zone
--   cities Прип'ять and Чорнобиль hang straight off Київська oblast with no
--   category-H parent at all. A NOT NULL column would be a lie for those three
--   and would have to be filled with something false.
--
--   What actually must hold is the narrower statement:
--
--       a row the oblast label cannot disambiguate MUST carry a hromada
--
--   which is exactly the constraint below. Measured on the import, all three
--   hromada-less rows have a UNIQUE name within their oblast, so the constraint
--   costs the data nothing while making the broken state unrepresentable.
--
-- ⚠ THIS CHECK IS NOT THE INERT SHAPE FROM `project_postgres_check_null_passes`
--   That memory records a constraint made silently vacuous by a NULLable column
--   in an OR chain: `NULL OR FALSE` is NULL, and PostgreSQL admits a row whose
--   CHECK evaluates to NULL. Here `ambiguous_in_oblast` is NOT NULL, so
--   `NOT ambiguous_in_oblast` is always TRUE or FALSE and the OR can never
--   evaluate to NULL. The only way through is a genuine TRUE.
--
--   Asserted, not asserted-about: V174CitiesHromadaDisambiguationMigrationTest
--   issues a raw INSERT with `ambiguous_in_oblast = TRUE` and a NULL hromada
--   against the migrated schema and requires PostgreSQL to reject it. A
--   constraint nobody has watched reject anything is not a constraint.
--
-- ORDER OF STATEMENTS IS LOAD-BEARING
--   The columns are added FIRST, with `DEFAULT FALSE` on the flag, so every
--   existing row satisfies the constraint at the moment it is created. The
--   constraint is then added and is live BEFORE V175 writes a single row —
--   which makes PostgreSQL itself the last line of defence against a CSV that
--   claims a row is ambiguous without saying what disambiguates it. The
--   generator refuses to emit such a row (it raises at build time), and this
--   would refuse to store one.
--
-- NO INDEX HERE, DELIBERATELY
--   Neither column is ever a predicate. `hromada_name_uk` is projected through
--   `CASE WHEN c.ambiguous_in_oblast THEN c.hromada_name_uk END` on two queries
--   that are already selected and ordered entirely by other columns
--   (idx_cities_name_uk_trgm for the search, idx_cities_major_name_uk for the
--   pre-typing list). An index on a column that appears only in a SELECT list
--   is write amplification on every future settlement import and buys nothing
--   (playbook §E-5 / §O-6).
--
-- WIDTH
--   VARCHAR(255) mirrors `cities.name_uk`, from which every hromada name is
--   derived — «Шишацька» from Шишаки. The longest in the 25 697-row import is
--   28 characters, so the bound is not a fit but a convention: a future rename
--   cannot overflow a column the source name itself could not.
--
-- IMMUTABILITY
--   V170-V173 are applied and `db/data/settlements.csv` is frozen by V171's
--   checksum. This ships forward as the next free version, and the hromada data
--   ships as a NEW resource rather than a widened settlements.csv, precisely so
--   that nothing already applied has to change (playbook §O-9).
-- ============================================================================

ALTER TABLE cities
    ADD COLUMN hromada_name_uk VARCHAR(255);

ALTER TABLE cities
    ADD COLUMN ambiguous_in_oblast BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN cities.hromada_name_uk IS
    'Bare hromada adjective from the settlement''s KATOTTH level_3 parent («Шишацька»), '
    'never the full «... селищна територіальна громада». NULL where the row has no '
    'category-H parent: Kyiv (category K) and the two exclusion-zone cities.';

COMMENT ON COLUMN cities.ambiguous_in_oblast IS
    'TRUE when another free settlement shares this row''s (name_uk, oblast_id) pair, so '
    '«‹назва›, ‹область›» cannot identify it on its own. Precomputed at import time by '
    'scripts/locality/build_settlement_import.py; 6 103 of 25 698 rows.';

ALTER TABLE cities
    ADD CONSTRAINT chk_cities_hromada_disambiguates
    CHECK (NOT ambiguous_in_oblast OR hromada_name_uk IS NOT NULL);
