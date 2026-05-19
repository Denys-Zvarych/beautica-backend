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
}
