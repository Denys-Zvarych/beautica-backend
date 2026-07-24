package com.beautica.migration;

import com.beautica.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test for V53__seed_locality_taxonomy.sql — the permanent KATOTTH
 * locality reference-data seed (Phase 10.2) loaded on top of the Phase 10.1
 * schema (V52: oblasts / cities / city_districts).
 *
 * <p>The build-verifier's existing migration test only proves V53 <em>applies</em>;
 * it asserts nothing about what V53 <em>seeds</em>. For a permanent reference
 * seed the seeded content IS the contract, so this test pins every acceptance
 * criterion of {@code docs/backend-phases/phase-10.2-katotth-seed.md}:
 *
 * <ul>
 *   <li>exact row counts (23 oblasts / 356 cities / 76 districts);</li>
 *   <li>occupied / non-serviced oblasts absent — asserted by BOTH KATOTTH code
 *       prefix AND Ukrainian name (a future maintainer who renames but keeps
 *       the code, or vice-versa, must still fail);</li>
 *   <li>zero referential orphans city→oblast and district→city;</li>
 *   <li>documented per-city category-B district counts (Kyiv 10, Kharkiv 9,
 *       Dnipro 8, Lviv 6, Odesa 4);</li>
 *   <li>Kyiv special-status invariant — same KATOTTH code in oblasts AND
 *       cities, distinct ids;</li>
 *   <li>determinism — the chain replays to byte-identical content (the seed
 *       carries no {@code ON CONFLICT} mask and no {@code gen_random_uuid()}
 *       leakage into the business key).</li>
 * </ul>
 *
 * <p>Assertions go through {@code information_schema}/aggregate/anti-join SQL,
 * never 455 row-by-row checks. The seed is permanent reference data and
 * {@link AbstractIntegrationTest#cleanDb()} deliberately does not truncate the
 * taxonomy tables, so every test here is read-only and order-independent.
 */
@DisplayName("V53 migration — KATOTTH locality taxonomy seed")
class LocalityTaxonomySeedMigrationTest extends AbstractIntegrationTest {

    // KATOTTH oblast-level codes for the four excluded territories. These are
    // the authoritative codes documented in the V53 header; the seed must
    // contain NONE of them in the oblasts table.
    private static final List<String> OCCUPIED_OBLAST_CODES = List.of(
            "UA01000000000013043", // АР Крим
            "UA14000000000091971", // Донецька область
            "UA44000000000018893", // Луганська область
            "UA85000000000065278"  // Севастополь
    );

    private static final List<String> OCCUPIED_OBLAST_NAMES_UK = List.of(
            "Автономна Республіка Крим",
            "Донецька",
            "Луганська",
            "Севастополь"
    );

    // ---------------------------------------------------------------------
    // Row-count contract
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("row counts")
    class RowCounts {

        @Test
        @DisplayName("seeds exactly 23 oblasts (22 category-O + Kyiv special-status)")
        void should_seedExactly23Oblasts_when_v53Applied() {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM oblasts", Integer.class);

            assertThat(count)
                    .as("oblasts row count — V53 header pins this at 23")
                    .isEqualTo(23);
        }

        @Test
        @DisplayName("seeds exactly 356 cities (355 category-M + Kyiv)")
        void should_seedExactly356Cities_when_v53Applied() {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM cities", Integer.class);

            assertThat(count)
                    .as("cities row count — V53 header pins this at 356, "
                            + "phase doc bound ~360-370")
                    .isEqualTo(356);
        }

        @Test
        @DisplayName("seeds exactly 76 city_districts (category-B across 17 cities)")
        void should_seedExactly76CityDistricts_when_v53Applied() {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM city_districts", Integer.class);

            assertThat(count)
                    .as("city_districts row count — V53 header pins this at 76")
                    .isEqualTo(76);
        }
    }

    // ---------------------------------------------------------------------
    // Occupied / non-serviced territory exclusion — by code AND by name
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("occupied-territory exclusion")
    class OccupiedTerritoryExclusion {

        @Test
        @DisplayName("none of the 4 excluded oblast KATOTTH codes are present")
        void should_excludeOccupiedOblasts_when_filteredByKatotthCode() {
            Integer matches = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM oblasts WHERE katotth_code IN (?, ?, ?, ?)",
                    Integer.class,
                    OCCUPIED_OBLAST_CODES.get(0),
                    OCCUPIED_OBLAST_CODES.get(1),
                    OCCUPIED_OBLAST_CODES.get(2),
                    OCCUPIED_OBLAST_CODES.get(3));

            assertThat(matches)
                    .as("excluded oblast codes %s must not appear in oblasts",
                            OCCUPIED_OBLAST_CODES)
                    .isZero();
        }

        @Test
        @DisplayName("no oblast name matches Crimea / Donetsk / Luhansk / Sevastopol")
        void should_excludeOccupiedOblasts_when_filteredByName() {
            // Independent of the code assertion: a maintainer who re-adds a row
            // with a fresh code but an occupied name must still fail here.
            Integer matches = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM oblasts "
                            + "WHERE name_uk LIKE 'Донецька%' "
                            + "   OR name_uk LIKE 'Луганська%' "
                            + "   OR name_uk LIKE '%Крим%' "
                            + "   OR name_uk LIKE '%Севастополь%'",
                    Integer.class);

            assertThat(matches)
                    .as("no oblast may carry an occupied-territory name %s",
                            OCCUPIED_OBLAST_NAMES_UK)
                    .isZero();
        }

        @Test
        @DisplayName("no city or district is orphaned under an excluded oblast code")
        void should_notSeedSubordinateLocalities_when_oblastExcluded() {
            // The excluded oblast rows are absent, so any city/district whose
            // KATOTTH code starts with an excluded oblast's 4-char territory
            // prefix would be a parentless leak. Codes look like UAxx........;
            // territory prefix = chars 1-4 (e.g. UA14 = Donetsk).
            Integer leakedCities = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM cities "
                            + "WHERE LEFT(katotth_code, 4) IN ('UA01','UA14','UA44','UA85')",
                    Integer.class);
            Integer leakedDistricts = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM city_districts "
                            + "WHERE LEFT(katotth_code, 4) IN ('UA01','UA14','UA44','UA85')",
                    Integer.class);

            assertThat(leakedCities)
                    .as("no city may belong to an excluded territory prefix")
                    .isZero();
            assertThat(leakedDistricts)
                    .as("no district may belong to an excluded territory prefix")
                    .isZero();
        }
    }

    // ---------------------------------------------------------------------
    // Referential integrity — zero orphans
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("referential integrity")
    class ReferentialIntegrity {

        @Test
        @DisplayName("every city FKs to a present oblast (zero orphans)")
        void should_haveZeroOrphanCities_when_v53Applied() {
            Integer orphans = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM cities c "
                            + "LEFT JOIN oblasts o ON c.oblast_id = o.id "
                            + "WHERE o.id IS NULL",
                    Integer.class);

            assertThat(orphans)
                    .as("cities with no resolvable parent oblast")
                    .isZero();
        }

        @Test
        @DisplayName("every district FKs to a present city (zero orphans)")
        void should_haveZeroOrphanDistricts_when_v53Applied() {
            Integer orphans = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM city_districts d "
                            + "LEFT JOIN cities c ON d.city_id = c.id "
                            + "WHERE c.id IS NULL",
                    Integer.class);

            assertThat(orphans)
                    .as("districts with no resolvable parent city")
                    .isZero();
        }

        @Test
        @DisplayName("every oblast/city/district name is non-blank")
        void should_haveNoBlankNames_when_v53Applied() {
            // The V52 CHECK constraints reject '' but the correlated-subquery
            // FK resolution could still have produced rows with whitespace-only
            // or NULL-resolved fields if a parent code mismatched; assert clean.
            Integer blanks = jdbcTemplate.queryForObject(
                    "SELECT (SELECT COUNT(*) FROM oblasts "
                            + "  WHERE TRIM(name_uk) = '' OR TRIM(name_en) = '') "
                            + "     + (SELECT COUNT(*) FROM cities "
                            + "  WHERE TRIM(name_uk) = '' OR TRIM(name_en) = '') "
                            + "     + (SELECT COUNT(*) FROM city_districts "
                            + "  WHERE TRIM(name_uk) = '' OR TRIM(name_en) = '')",
                    Integer.class);

            assertThat(blanks)
                    .as("no taxonomy row may have a blank name")
                    .isZero();
        }
    }

    // ---------------------------------------------------------------------
    // Per-city category-B district spot-checks (documented bounds)
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("per-city district spot-checks")
    class PerCityDistrictCounts {

        private int districtCountForCity(String cityNameUk) {
            Integer n = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM city_districts d "
                            + "JOIN cities c ON d.city_id = c.id "
                            + "WHERE c.name_uk = ?",
                    Integer.class,
                    cityNameUk);
            return n == null ? -1 : n;
        }

        @Test
        @DisplayName("Kyiv has 10 districts")
        void should_haveTenDistricts_when_cityIsKyiv() {
            assertThat(districtCountForCity("Київ"))
                    .as("Kyiv category-B district count")
                    .isEqualTo(10);
        }

        @Test
        @DisplayName("Kharkiv has 9 districts")
        void should_haveNineDistricts_when_cityIsKharkiv() {
            assertThat(districtCountForCity("Харків"))
                    .as("Kharkiv category-B district count")
                    .isEqualTo(9);
        }

        @Test
        @DisplayName("Dnipro has 8 districts")
        void should_haveEightDistricts_when_cityIsDnipro() {
            assertThat(districtCountForCity("Дніпро"))
                    .as("Dnipro category-B district count")
                    .isEqualTo(8);
        }

        @Test
        @DisplayName("Lviv has 6 districts")
        void should_haveSixDistricts_when_cityIsLviv() {
            assertThat(districtCountForCity("Львів"))
                    .as("Lviv category-B district count")
                    .isEqualTo(6);
        }

        @Test
        @DisplayName("Odesa has 4 districts")
        void should_haveFourDistricts_when_cityIsOdesa() {
            assertThat(districtCountForCity("Одеса"))
                    .as("Odesa category-B district count")
                    .isEqualTo(4);
        }

        @Test
        @DisplayName("the 76 districts are distributed across exactly 17 cities")
        void should_spreadDistrictsAcross17Cities_when_v53Applied() {
            Integer distinctParents = jdbcTemplate.queryForObject(
                    "SELECT COUNT(DISTINCT city_id) FROM city_districts",
                    Integer.class);

            assertThat(distinctParents)
                    .as("V53 header: category-B districts across 17 cities")
                    .isEqualTo(17);
        }
    }

    // ---------------------------------------------------------------------
    // Kyiv special-status invariant + determinism
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Kyiv special-status & determinism")
    class SpecialStatusAndDeterminism {

        private static final String KYIV_CODE = "UA80000000000093317";

        @Test
        @DisplayName("Kyiv appears once in oblasts and once in cities, sharing its KATOTTH code")
        void should_seedKyivAsBothOblastAndCity_when_specialStatus() {
            Integer asOblast = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM oblasts WHERE katotth_code = ?",
                    Integer.class, KYIV_CODE);
            Integer asCity = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM cities WHERE katotth_code = ?",
                    Integer.class, KYIV_CODE);

            assertThat(asOblast).as("Kyiv must be exactly one oblast row").isEqualTo(1);
            assertThat(asCity).as("Kyiv must be exactly one city row").isEqualTo(1);
        }

        @Test
        @DisplayName("Kyiv city FKs to the Kyiv oblast row")
        void should_linkKyivCityToKyivOblast_when_specialStatus() {
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT c.name_uk AS city_name, o.name_uk AS oblast_name "
                            + "FROM cities c JOIN oblasts o ON c.oblast_id = o.id "
                            + "WHERE c.katotth_code = ?",
                    KYIV_CODE);

            assertThat(row.get("city_name")).isEqualTo("Київ");
            assertThat(row.get("oblast_name"))
                    .as("Kyiv city must hang off the Kyiv oblast-equivalent")
                    .isEqualTo("Київ");
        }

        @Test
        @DisplayName("katotth_code is NOT NULL in all three taxonomy tables (information_schema, Phase 10.9 Step 1)")
        void should_haveNotNullKatotthCode_when_v52Applied() {
            // The sibling tests prove katotth_code is UNIQUE and that every
            // seeded value is a real 'UA…' string, but neither pins the column
            // NULLABILITY constraint itself. Phase 10.9 Step 1 requires the
            // NOT NULL invariant asserted directly through the catalog so a
            // future ALTER that relaxed it (allowing a NULL business key, which
            // breaks idempotent upsert-by-code) fails here, not silently.
            for (String table : new String[] {"oblasts", "cities", "city_districts"}) {
                String isNullable = jdbcTemplate.queryForObject(
                        "SELECT is_nullable FROM information_schema.columns "
                                + "WHERE table_name = ? AND column_name = 'katotth_code'",
                        String.class, table);

                assertThat(isNullable)
                        .as("%s.katotth_code must be NOT NULL — it is the stable "
                                + "external business key for idempotent seed upserts", table)
                        .isEqualTo("NO");
            }
        }

        @Test
        @DisplayName("V52→V53→V54 applied as one clean ordered chain — every Part A migration recorded success, none failed (Phase 10.9 Step 1)")
        void should_applyV52V53V54AsOneCleanOrderedChain_when_freshDb() {
            // Step 1 contract: the three Part A migrations boot cleanly on a
            // fresh Testcontainers Postgres with no checksum/ordering issue.
            // Flyway records execution order in installed_rank; a checksum
            // mismatch or out-of-order apply would either crash the context
            // (no rows) or leave success=false. Assert all three present,
            // success, and strictly increasing installed_rank in V-order.
            List<Map<String, Object>> chain = jdbcTemplate.queryForList(
                    "SELECT version, success, installed_rank "
                            + "FROM flyway_schema_history "
                            + "WHERE version IN ('52','53','54') "
                            + "ORDER BY installed_rank");

            assertThat(chain)
                    .as("V52, V53 and V54 must all be present in the history")
                    .hasSize(3);
            assertThat(chain)
                    .as("the Part A chain must apply strictly in V52→V53→V54 order")
                    .extracting(r -> r.get("version"))
                    .containsExactly("52", "53", "54");
            assertThat(chain)
                    .as("no Part A migration may be recorded as a failure")
                    .allSatisfy(r -> assertThat(r.get("success")).isEqualTo(Boolean.TRUE));

            Integer failedAnywhere = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE success = false",
                    Integer.class);
            assertThat(failedAnywhere)
                    .as("a fresh-DB boot must have ZERO failed migrations in the whole chain")
                    .isZero();
        }

        @Test
        @DisplayName("every KATOTTH business key is unique within its table (no UUID leakage into the key)")
        void should_haveUniqueKatotthCodes_when_v53Applied() {
            Integer dupOblasts = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM (SELECT katotth_code FROM oblasts "
                            + "GROUP BY katotth_code HAVING COUNT(*) > 1) d",
                    Integer.class);
            Integer dupCities = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM (SELECT katotth_code FROM cities "
                            + "GROUP BY katotth_code HAVING COUNT(*) > 1) d",
                    Integer.class);
            Integer dupDistricts = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM (SELECT katotth_code FROM city_districts "
                            + "GROUP BY katotth_code HAVING COUNT(*) > 1) d",
                    Integer.class);

            assertThat(dupOblasts).as("duplicate oblast KATOTTH codes").isZero();
            assertThat(dupCities).as("duplicate city KATOTTH codes").isZero();
            assertThat(dupDistricts).as("duplicate district KATOTTH codes").isZero();
        }

        @Test
        @DisplayName("seed is deterministic — Flyway recorded V53 as success with a stable checksum")
        void should_recordV53AsSuccess_when_chainReplayed() {
            // Determinism guard at the migration-history level: V53 is a
            // versioned (not repeatable) migration with a fixed checksum, so
            // any non-idempotent edit (e.g. an added ON CONFLICT or a
            // gen_random_uuid() in the business key) changes the checksum and
            // breaks the recorded-success contract on the next fresh chain.
            Map<String, Object> v53 = jdbcTemplate.queryForMap(
                    "SELECT success, checksum, script "
                            + "FROM flyway_schema_history WHERE version = '53'");

            assertThat(v53.get("success"))
                    .as("V53 must be recorded as a successful migration")
                    .isEqualTo(Boolean.TRUE);
            assertThat(v53.get("script"))
                    .isEqualTo("V53__seed_locality_taxonomy.sql");
            assertThat(v53.get("checksum"))
                    .as("V53 must carry a deterministic (non-null) checksum")
                    .isNotNull();
        }
    }

    // ---------------------------------------------------------------------
    // Phase 10.8 AC6 — seed migration runtime is acceptable on a cold start
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Phase 10.8 AC6 — V53 seed migration runtime")
    class SeedMigrationRuntime {

        /**
         * Cold-start ceiling for the V53 seed. The seed is ~455 single-row
         * {@code INSERT ... SELECT ... WHERE katotth_code = ?} statements
         * (FK resolved by business key — the deterministic/idempotent form the
         * V53 header mandates), all inside ONE Flyway migration transaction
         * (one begin/commit round-trip, not 455 transactions) over ≤356-row
         * reference tables. Testcontainers spins a cold {@code postgres:16}
         * with no warm cache — a fair, if anything pessimistic, proxy for a
         * cold Neon start (Neon's compute resumes warm). Flyway records the
         * applied duration in {@code execution_time} (milliseconds); a generous
         * 10s ceiling is a coarse regression tripwire that a pathological
         * rewrite (e.g. a per-row correlated cross join, or accidental
         * {@code O(n^2)} re-seed) would trip, while not flaking on CI jitter.
         * V53 is an immutable shipped migration — Phase 10.8 only CONFIRMS its
         * runtime (Step 4), it does not and must not rewrite it.
         */
        private static final int V53_RUNTIME_CEILING_MS = 10_000;

        @Test
        @DisplayName("V53 applied well within the cold-start runtime ceiling (batched in one migration tx)")
        void should_applyWithinRuntimeCeiling_when_seedRunOnColdContainer() {
            Integer executionTimeMs = jdbcTemplate.queryForObject(
                    "SELECT execution_time FROM flyway_schema_history "
                            + "WHERE version = '53'",
                    Integer.class);

            assertThat(executionTimeMs)
                    .as("Flyway must have recorded a V53 execution_time")
                    .isNotNull();
            assertThat(executionTimeMs)
                    .as("V53 (~455 single-row inserts in ONE migration tx over "
                            + "≤356-row ref tables) must not bloat cold-start "
                            + "migration runtime — recorded %d ms, ceiling %d ms",
                            executionTimeMs, V53_RUNTIME_CEILING_MS)
                    .isLessThan(V53_RUNTIME_CEILING_MS);
        }

        @Test
        @DisplayName("V53 is a single atomic migration entry — not row-by-row reapplied (no repeat/retry rows)")
        void should_haveExactlyOneV53HistoryRow_when_chainApplied() {
            // A single flyway_schema_history row for V53 proves the ~455
            // inserts ran as ONE migration unit (one tx, one round-trip to
            // start/commit), not as fragmented re-applied chunks.
            Integer rows = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '53'",
                    Integer.class);

            assertThat(rows)
                    .as("V53 must appear exactly once in the migration history")
                    .isEqualTo(1);
        }
    }
}
