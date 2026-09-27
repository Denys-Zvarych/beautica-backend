package com.beautica.migration;

import com.beautica.AbstractIntegrationTest;
import com.beautica.support.OccupiedSettlementCodes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DataAccessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract test for the permanent KATOTTH locality reference data — V53's Phase 10.2 seed on top
 * of the Phase 10.1 schema (V52), as widened by Phase 325 (V170 + V171).
 *
 * <p><b>Phase 325 retargeted several assertions here, and they are not cosmetic.</b> V53 filtered
 * occupied territory at OBLAST level, which had two consequences this test used to encode as the
 * contract: the free Донецька/Луганська settlements were missing, and — because Запорізька and
 * Херсонська were kept wholesale — SEVENTEEN genuinely occupied cities (Мелітополь, Бердянськ,
 * Енергодар, Нова Каховка, Скадовськ, …) were seeded. V170 deletes those 17 and adds the two
 * oblast rows; V171 imports the 25 697-row free-settlement list. The occupied-territory
 * assertions below are therefore STRONGER than they were, not weaker: they moved from "no row
 * under four oblast prefixes" to "no row that Phase 324 lists as occupied".
 *
 * <p>The build-verifier's existing migration test only proves V53 <em>applies</em>;
 * it asserts nothing about what V53 <em>seeds</em>. For a permanent reference
 * seed the seeded content IS the contract, so this test pins every acceptance
 * criterion of {@code docs/backend-phases/phase-10.2-katotth-seed.md}:
 *
 * <ul>
 *   <li>exact row counts (25 oblasts / 25 698 cities / 76 districts);</li>
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

    // KATOTTH oblast-level codes excluded WHOLESALE (phase-325 D1): occupied in full since 2014,
    // with no settlement-level reasoning to do. Донецька (UA14) and Луганська (UA44) are NOT here
    // any more — Phase 325 filters them at SETTLEMENT level, so their oblast rows must exist to
    // parent Краматорськ, Слов'янськ and м. Лиман.
    private static final List<String> EXCLUDED_OBLAST_CODES = List.of(
            "UA01000000000013043", // АР Крим
            "UA85000000000065278"  // Севастополь
    );

    private static final List<String> EXCLUDED_OBLAST_NAMES_UK = List.of(
            "Автономна Республіка Крим",
            "Севастополь"
    );

    // The 17 cities V53 seeded that Phase 324 lists as currently occupied — V53's oblast-level
    // filter kept Запорізька and Херсонська wholesale. V170 deletes them; V171 never re-offers
    // them, because the import CSV is pre-filtered. This list IS the regression these two
    // migrations exist to close.
    private static final List<String> V53_SEEDED_OCCUPIED_CITY_CODES = List.of(
            "UA23020050010019935", // Бердянськ
            "UA23020130010076068", // Приморськ
            "UA23040030010016724", // Василівка
            "UA23040090010050034", // Дніпрорудне
            "UA23040110010044100", // Енергодар
            "UA23040130010014334", // Кам'янка-Дніпровська
            "UA23080070010092407", // Мелітополь
            "UA23100150010091297", // Молочанськ
            "UA23100190010032690", // Пологи
            "UA23100270010029314", // Токмак
            "UA65040010010040633", // Генічеськ
            "UA65060110010021041", // Каховка
            "UA65060170010075325", // Нова Каховка
            "UA65060250010044738", // Таврійськ
            "UA65080030010035864", // Гола Пристань
            "UA65080150010023642", // Скадовськ
            "UA65100110010019482"  // Олешки
    );

    // Government-controlled Донецька settlements. Their presence is the whole point of moving the
    // filter from oblast level to settlement level — if these are missing, the oblast-level scrub
    // is still in force.
    private static final List<String> FREE_DONETSK_CITY_CODES = List.of(
            "UA14120090010038661", // Краматорськ
            "UA14120210010032554", // Слов'янськ
            "UA14120110010088407"  // м. Лиман
    );

    // ---------------------------------------------------------------------
    // Row-count contract
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("row counts")
    class RowCounts {

        @Test
        @DisplayName("holds exactly 25 oblasts (22 category-O + Донецька + Луганська + Kyiv)")
        void should_holdExactly25Oblasts_when_v170Applied() {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM oblasts", Integer.class);

            assertThat(count)
                    .as("oblasts row count — V53's 23 plus the two oblasts V170 restores; "
                            + "Crimea and Sevastopol stay out (D1)")
                    .isEqualTo(25);
        }

        /**
         * Replaces a {@code SELECT COUNT(*) FROM cities == 25 698} assertion that <b>could not
         * fail</b>. {@code V171__import_free_settlements#verifyFinalState} (:301-307) aborts the
         * migration on exactly that condition, so a wrong total crashes the Spring context and
         * ERRORS every test in this class — it never reaches an assertion, and the one that
         * looked like it covered the total actually reported nothing. A test that cannot go red
         * is worse than no test: it reads as coverage.
         *
         * <p>The per-type split is the part V171 does NOT enforce, and it is the part the
         * product depends on: the cascading picker and V172's partial index are both bounded to
         * {@code CITY}, so a CSV whose {@code settlement_type} column shifted — same 25 697 rows,
         * different types — would pass every migration guard and silently empty or flood the
         * picker. Summing the four buckets back to the table total keeps the row-count contract
         * without restating the migration's own precondition.
         */
        @Test
        @DisplayName("the settlement_type split is 353 CITY / 1 332 SETTLEMENT / 24 013 VILLAGE / 0 TOWN")
        void should_holdTheCuratedSettlementTypeSplit_when_v171Applied() {
            Map<String, Object> split = jdbcTemplate.queryForMap("""
                    SELECT
                        COUNT(*) FILTER (WHERE settlement_type = 'CITY')       AS cities,
                        COUNT(*) FILTER (WHERE settlement_type = 'TOWN')       AS towns,
                        COUNT(*) FILTER (WHERE settlement_type = 'VILLAGE')    AS villages,
                        COUNT(*) FILTER (WHERE settlement_type = 'SETTLEMENT') AS settlements,
                        COUNT(*)                                               AS total
                      FROM cities
                    """);

            assertThat(((Number) split.get("cities")).intValue())
                    .as("352 category-M rows from the CSV + Kyiv (category K, seeded by V53) — "
                            + "this is the whole population of the oblast->city picker tier and "
                            + "of V172's partial index")
                    .isEqualTo(353);
            assertThat(((Number) split.get("towns")).intValue())
                    .as("TOWN is a legal settlement_type the 2025-07-02 classifier never emits: "
                            + "the селище-міського-типу category was retired and those places "
                            + "carry X/SETTLEMENT now. A non-zero count means the CSV was built "
                            + "from a different classifier version than V170's CHECK assumes")
                    .isZero();
            assertThat(((Number) split.get("villages")).intValue()).isEqualTo(24_013);
            assertThat(((Number) split.get("settlements")).intValue()).isEqualTo(1_332);
            assertThat(((Number) split.get("total")).intValue())
                    .as("the four buckets must account for every row — a fifth value would have "
                            + "to violate V170's settlement_type CHECK to exist")
                    .isEqualTo(353 + 0 + 24_013 + 1_332);
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
        @DisplayName("neither wholesale-excluded oblast KATOTTH code is present")
        void should_excludeCrimeaAndSevastopol_when_filteredByKatotthCode() {
            Integer matches = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM oblasts WHERE katotth_code IN (?, ?)",
                    Integer.class,
                    EXCLUDED_OBLAST_CODES.get(0),
                    EXCLUDED_OBLAST_CODES.get(1));

            assertThat(matches)
                    .as("wholesale-excluded oblast codes %s must not appear in oblasts",
                            EXCLUDED_OBLAST_CODES)
                    .isZero();
        }

        @Test
        @DisplayName("no oblast name matches Crimea / Sevastopol")
        void should_excludeCrimeaAndSevastopol_when_filteredByName() {
            // Independent of the code assertion: a maintainer who re-adds a row
            // with a fresh code but an excluded name must still fail here.
            Integer matches = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM oblasts "
                            + "WHERE name_uk LIKE '%Крим%' "
                            + "   OR name_uk LIKE '%Севастополь%'",
                    Integer.class);

            assertThat(matches)
                    .as("no oblast may carry a wholesale-excluded name %s",
                            EXCLUDED_OBLAST_NAMES_UK)
                    .isZero();
        }

        @Test
        @DisplayName("no city or district carries a UA01 / UA85 territory prefix")
        void should_notSeedSubordinateLocalities_when_oblastExcludedWholesale() {
            // The excluded oblast rows are absent, so any city/district whose KATOTTH code starts
            // with one of their 4-char territory prefixes would be a parentless leak. UA14/UA44
            // are deliberately NOT in this list any more: Донецька and Луганська are filtered at
            // settlement level, so free rows under those prefixes are expected and correct.
            Integer leakedCities = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM cities "
                            + "WHERE LEFT(katotth_code, 4) IN ('UA01','UA85')",
                    Integer.class);
            Integer leakedDistricts = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM city_districts "
                            + "WHERE LEFT(katotth_code, 4) IN ('UA01','UA85')",
                    Integer.class);

            assertThat(leakedCities)
                    .as("no city may belong to a wholesale-excluded territory prefix")
                    .isZero();
            assertThat(leakedDistricts)
                    .as("no district may belong to a wholesale-excluded territory prefix")
                    .isZero();
        }

        @Test
        @DisplayName("none of the 17 occupied cities V53 seeded survive (V170 purge)")
        void should_holdNoOccupiedCity_when_v170Applied() {
            List<Map<String, Object>> survivors = jdbcTemplate.queryForList(
                    "SELECT katotth_code, name_uk FROM cities WHERE katotth_code = ANY (?)",
                    new Object[] {V53_SEEDED_OCCUPIED_CITY_CODES.toArray(new String[0])});

            assertThat(survivors)
                    .as("Phase 324 lists every one of these as currently occupied; V53 seeded "
                            + "them because its filter ran at oblast level and kept Запорізька "
                            + "and Херсонська wholesale")
                    .isEmpty();
        }

        @Test
        @DisplayName("holds no code from the Phase 324 exclusion set — all 2 958, not just the 17 V53 seeded")
        void should_holdNoCodeFromThePhase324ExclusionSet_when_v171Applied() {
            // The sibling assertions above cover the UA01/UA85 prefixes and the 17 cities V53
            // seeded by mistake. That left 2 941 of Phase 324's 2 958 codes unasserted — and those
            // are precisely the ones the phase's mechanism is supposed to handle, since UA14/UA44
            // are now filtered at SETTLEMENT level and their free rows ARE expected to be present.
            // With no occupation_status column and no runtime predicate (D3), absence from this
            // table is the entire compliance story; this is the assertion that it holds.
            List<String> excluded = OccupiedSettlementCodes.load();
            assertThat(excluded)
                    .as("guard armed — a shrunken exclusion resource would narrow the query below "
                            + "to a vacuous match")
                    .hasSize(OccupiedSettlementCodes.EXPECTED_CODE_COUNT);

            List<Map<String, Object>> survivors = jdbcTemplate.queryForList(
                    "SELECT katotth_code FROM cities WHERE katotth_code = ANY (?)",
                    new Object[] {excluded.toArray(new String[0])});

            assertThat(survivors)
                    .as("every code in docs/qa/occupied-settlements.md names a currently occupied "
                            + "settlement. Phase 325 D3 keeps them out by filtering the import "
                            + "SOURCE, so a row here is an unfiltered CSV reaching production — "
                            + "not a query that forgot a WHERE clause. Survivors: %s",
                            survivors)
                    .isEmpty();
        }

        @Test
        @DisplayName("no oblast or city_district carries an occupied KATOTTH code either")
        void should_holdNoOccupiedCodeInTheSiblingTaxonomyTables_when_v171Applied() {
            // `cities` is where the leak would be visible first, but it is not the only table the
            // address picker reads. oblasts and city_districts carry their own KATOTTH codes from
            // the same classifier, so the same published set is asserted against both rather than
            // assuming the FK to `cities` makes them safe by construction.
            String[] excluded = OccupiedSettlementCodes.load().toArray(new String[0]);

            Integer inOblasts = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM oblasts WHERE katotth_code = ANY (?)",
                    Integer.class, new Object[] {excluded});
            Integer inDistricts = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM city_districts WHERE katotth_code = ANY (?)",
                    Integer.class, new Object[] {excluded});

            assertThat(inOblasts).as("oblasts carrying an occupied settlement code").isZero();
            assertThat(inDistricts)
                    .as("city_districts carrying an occupied settlement code")
                    .isZero();
        }

        @Test
        @DisplayName("no user or salon still references one of the 17 purged cities")
        void should_leaveNoDanglingLocalityReference_when_occupiedCitiesPurged() {
            // The purge is only safe if nothing was left pointing into the hole. salons.city_id is
            // NOT NULL so V170 aborts rather than orphan one; users.city_id is cleared. Either way
            // the post-condition is the same and is asserted against the real schema.
            Integer orphanUsers = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM users u "
                            + "WHERE u.city_id IS NOT NULL "
                            + "  AND NOT EXISTS (SELECT 1 FROM cities c WHERE c.id = u.city_id)",
                    Integer.class);
            Integer orphanSalons = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM salons s "
                            + "WHERE NOT EXISTS (SELECT 1 FROM cities c WHERE c.id = s.city_id)",
                    Integer.class);

            assertThat(orphanUsers).as("users pointing at a deleted city").isZero();
            assertThat(orphanSalons).as("salons pointing at a deleted city").isZero();
        }
    }

    // ---------------------------------------------------------------------
    // Phase 325 — the settlement-level filter's positive half
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Phase 325 — full settlement taxonomy")
    class FullSettlementTaxonomy {

        @Test
        @DisplayName("the free Донецька cities ARE present — the settlement-level filter's point")
        void should_holdFreeDonetskCities_when_v171Applied() {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT c.katotth_code, c.name_uk, o.name_uk AS oblast "
                            + "FROM cities c JOIN oblasts o ON o.id = c.oblast_id "
                            + "WHERE c.katotth_code = ANY (?)",
                    new Object[] {FREE_DONETSK_CITY_CODES.toArray(new String[0])});

            assertThat(rows)
                    .as("Краматорськ, Слов'янськ and м. Лиман are government-controlled. Their "
                            + "absence means the oblast-level scrub is still in force.")
                    .hasSize(FREE_DONETSK_CITY_CODES.size());
            assertThat(rows)
                    .allSatisfy(r -> assertThat(r.get("oblast")).isEqualTo("Донецька"));
        }

        @Test
        @DisplayName("villages dominate the taxonomy and every row carries a legal settlement_type")
        void should_carryASettlementTypeForEveryRow_when_v171Applied() {
            Integer illegal = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM cities "
                            + "WHERE settlement_type IS NULL "
                            + "   OR settlement_type NOT IN ('CITY','TOWN','VILLAGE','SETTLEMENT')",
                    Integer.class);
            Integer villages = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM cities WHERE settlement_type = 'VILLAGE'", Integer.class);

            assertThat(illegal).as("settlement_type outside the enum").isZero();
            assertThat(villages)
                    .as("the taxonomy exists so a master in a village has somewhere to say they "
                            + "work — villages must be the bulk of it")
                    .isEqualTo(24_013);
        }

        /**
         * The {@code COUNT(*) WHERE is_major == 50} assertion this test used to lead with was
         * DELETED for the same reason as the 25 698 total above: {@code V171:309-313} aborts the
         * migration when the count is not 50, so the assertion could only ever be reached after
         * the migration had already proved it. What remains are the two properties of the
         * curation that V171 does not check.
         */
        @Test
        @DisplayName("every is_major row is a CITY, spread over the 24 oblasts that have one")
        void should_curateMajorSettlementsCleanly_when_v171Applied() {
            Integer majorNonCity = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM cities WHERE is_major AND settlement_type <> 'CITY'",
                    Integer.class);
            Integer oblastsWithAMajor = jdbcTemplate.queryForObject(
                    "SELECT COUNT(DISTINCT oblast_id) FROM cities WHERE is_major", Integer.class);
            Integer luhanskMajors = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM cities c JOIN oblasts o ON o.id = c.oblast_id "
                            + "WHERE c.is_major AND o.katotth_code = ?",
                    Integer.class, "UA44000000000018893");

            assertThat(majorNonCity)
                    .as("a village or селище in the pre-typing 'biggest places' list is a "
                            + "curation bug — the list is hand-maintained in "
                            + "scripts/locality/build_settlement_import.py and nothing in V170 or "
                            + "V171 constrains WHICH rows carry the flag")
                    .isZero();
            assertThat(oblastsWithAMajor)
                    .as("23 oblasts contribute a major settlement, plus Kyiv, which is its own "
                            + "oblast-equivalent — so 24 of the 25 oblast rows")
                    .isEqualTo(24);
            assertThat(luhanskMajors)
                    .as("Луганська is the one oblast with no major settlement: all 13 of its free "
                            + "settlements are villages/селища. This is the same fact that makes "
                            + "it a dead end in the CITY-bounded cascading picker, which is why "
                            + "LocationQueryService#listOblasts no longer offers it")
                    .isZero();
        }

        @Test
        @DisplayName("duplicate settlement names resolve by oblast — «Іванівка» exists many times over")
        void should_resolveDuplicateNamesByOblast_when_v171Applied() {
            // This is the concrete reason phase-325 D1 keeps oblast_id: the product drops the
            // oblast INPUT, not the oblast DATA, because the picker label has to disambiguate.
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT COUNT(*) AS n, COUNT(DISTINCT oblast_id) AS oblasts "
                            + "FROM cities WHERE name_uk = 'Іванівка'");

            assertThat(((Number) row.get("n")).intValue())
                    .as("«Іванівка» rows")
                    .isGreaterThan(1);
            assertThat(((Number) row.get("oblasts")).intValue())
                    .as("each «Іванівка» must be separable by its oblast")
                    .isGreaterThan(1);
        }

        @Test
        @DisplayName("V170 and V171 both recorded success and V170 left no staging table behind")
        void should_recordBothPhase325MigrationsAsSuccess_when_chainApplied() {
            List<Map<String, Object>> chain = jdbcTemplate.queryForList(
                    "SELECT version, success FROM flyway_schema_history "
                            + "WHERE version IN ('170','171') ORDER BY installed_rank");
            Integer staging = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables "
                            + "WHERE table_name = 'v170_occupied_city_ids'",
                    Integer.class);

            assertThat(chain)
                    .extracting(r -> r.get("version"))
                    .containsExactly("170", "171");
            assertThat(chain)
                    .allSatisfy(r -> assertThat(r.get("success")).isEqualTo(Boolean.TRUE));
            assertThat(staging)
                    .as("V170's staging table must not outlive the migration")
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

    // ---------------------------------------------------------------------
    // Phase 325 — the schema objects V170 adds, asserted as OBJECTS
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Phase 325 — V170 schema objects")
    class Phase325SchemaObjects {

        @Test
        @DisplayName("chk_cities_settlement_type actually rejects a value outside the enum")
        void should_rejectAnIllegalSettlementType_when_insertAttempted() {
            // The sibling assertion "no row holds an illegal settlement_type" is satisfied by a
            // CHECK that does not exist at all — it only reads what V171 happened to write. This
            // one exercises the constraint, which is the only way to know it is not inert.
            UUID oblastId = jdbcTemplate.queryForObject(
                    "SELECT id FROM oblasts LIMIT 1", UUID.class);

            assertThatThrownBy(() -> jdbcTemplate.update(
                    "INSERT INTO cities (oblast_id, katotth_code, name_uk, name_en, "
                            + "settlement_type, is_major) "
                            + "VALUES (?, 'UA99999999999999999', 'Fixture', 'Fixture', "
                            + "'HAMLET', false)",
                    oblastId))
                    .as("a settlement_type outside CITY/TOWN/VILLAGE/SETTLEMENT must be refused "
                            + "by the database, not merely absent from today's data")
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("chk_cities_settlement_type");
        }

        @Test
        @DisplayName("settlement_type is NOT NULL — V170 drops the backfill default so inserts must state it")
        void should_rejectANullSettlementType_when_insertOmitsIt() {
            UUID oblastId = jdbcTemplate.queryForObject(
                    "SELECT id FROM oblasts LIMIT 1", UUID.class);

            assertThatThrownBy(() -> jdbcTemplate.update(
                    "INSERT INTO cities (oblast_id, katotth_code, name_uk, name_en, is_major) "
                            + "VALUES (?, 'UA99999999999999998', 'Fixture', 'Fixture', false)",
                    oblastId))
                    .as("the DEFAULT 'CITY' is a one-shot backfill device and V170 drops it — an "
                            + "insert that omits the type would otherwise mislabel a village")
                    .isInstanceOf(DataAccessException.class);
        }

        @Test
        @DisplayName("idx_cities_major_name_uk exists as a PARTIAL index on is_major, ordered by name_uk")
        void should_createThePartialMajorIndex_when_v170Applied() {
            // A plain index on is_major would satisfy "an index exists" while scanning 25 698
            // entries to find 50. The WHERE clause and the ORDER key are the point, so both are
            // read out of pg_indexes rather than assumed.
            String definition = jdbcTemplate.queryForObject(
                    "SELECT indexdef FROM pg_indexes "
                            + "WHERE tablename = 'cities' AND indexname = 'idx_cities_major_name_uk'",
                    String.class);

            assertThat(definition)
                    .as("V170 ships this index specifically to serve "
                            + "`WHERE is_major ORDER BY name_uk`")
                    .isNotNull()
                    .contains("(name_uk)")
                    .contains("WHERE is_major");
        }

        @Test
        @DisplayName("Kyiv carries is_major — the category-K row V171 flags by hand, absent from the CSV")
        void should_flagKyivAsMajor_when_v171Applied() {
            // Kyiv is KATOTTH category K, so it is not a level-4 settlement and never appears in
            // settlements.csv. The "exactly 50 major" count would still hold if V171's dedicated
            // Kyiv UPDATE were deleted and a 50th CSV row flagged instead, so the capital is
            // pinned by name.
            Boolean major = jdbcTemplate.queryForObject(
                    "SELECT is_major FROM cities WHERE katotth_code = ?",
                    Boolean.class, "UA80000000000093317");

            assertThat(major)
                    .as("the capital must head the pre-typing 'biggest places' list")
                    .isTrue();
        }

        @Test
        @DisplayName("every major settlement is distinct by name — the pre-typing list shows no ambiguous label")
        void should_carryUnambiguousMajorNames_when_v171Applied() {
            // The pre-typing list renders name_uk alone. Two majors sharing a name would render as
            // two identical rows the user cannot tell apart — the exact failure the oblast column
            // exists to prevent, and one the 50-count assertion cannot see.
            List<Map<String, Object>> ambiguous = jdbcTemplate.queryForList(
                    "SELECT name_uk FROM cities WHERE is_major "
                            + "GROUP BY name_uk HAVING COUNT(*) > 1");

            assertThat(ambiguous)
                    .as("duplicate labels in the 'biggest places' list")
                    .isEmpty();
        }
    }
}
