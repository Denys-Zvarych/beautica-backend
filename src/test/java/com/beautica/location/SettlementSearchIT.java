package com.beautica.location;

import com.beautica.AbstractIntegrationTest;
import com.beautica.support.HibernateStatistics;
import com.github.benmanes.caffeine.cache.Cache;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-stack integration test for the Phase 326 settlement autocomplete, driven end to end
 * (HTTP -> controller -> service -> GIN-indexed query) against the <em>real</em> V170/V171-migrated
 * taxonomy: 25 698 settlements, 50 of them {@code is_major}.
 *
 * <p><b>Every acceptance criterion in this class is asserted against the SEEDED data, never a
 * fixture.</b> A fixture would let «Мелітополь returns nothing» pass against an empty table, and
 * would let the ranking pass against three hand-picked rows that cannot reproduce the 99
 * «Іванівка», the three «Львів» or the «Івано-Франкове» that outscores «Івано-Франківськ» on raw
 * similarity. The ranking is only meaningful at the real cardinality.
 *
 * <p><b>The occupied-settlement test here is a REGRESSION guard on this endpoint, not the ban's
 * enforcement surface.</b> Phase 324's exclusion set is applied to the import SOURCE (phase-325
 * D3), so no occupied row exists to filter and there is no {@code occupation_status} column to
 * consult; {@code SettlementImportCsvExclusionTest} and {@code LocalityTaxonomySeedMigrationTest}
 * are where the ban is enforced. It still earns its place: it catches a future change that
 * reintroduces occupied rows by some other door, through the one surface that would surface them
 * to a user.
 *
 * <p>The plan shape and the index definition are {@code V173SettlementTrigramIndexMigrationTest}'s.
 * The normalisation and the argument binding are {@code SettlementSearchServiceTest}'s. The JSON
 * contract is {@code SettlementSearchControllerTest}'s. This class owns only what needs all of
 * them at once plus real rows.
 *
 * <p>{@link AbstractIntegrationTest#cleanDb()} deliberately does not truncate the taxonomy tables,
 * so every test here is read-only and order-independent.
 */
@DisplayName("Phase 326 settlement autocomplete — full-flow integration")
class SettlementSearchIT extends AbstractIntegrationTest {

    private static final String URL = "/api/v1/settlements";

    /**
     * The hard result cap (phase-326 D8), restated here rather than imported.
     * {@code SettlementSearchService.MAX_RESULTS} is package-private and this test lives in the
     * parent package; widening it to public so a test can read it would export an implementation
     * detail on the strength of a test. The number is the phase decision — if it changes, the
     * phase doc and this constant change together, and that is the intended friction.
     */
    private static final int MAX_RESULTS = 20;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CacheManager cacheManager;

    /** Statement-count probe for the zero-trigram guard — see {@link HibernateStatistics}. */
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private static HttpEntity<Void> anonymous() {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        return new HttpEntity<>(headers);
    }

    /**
     * Performs the anonymous GET and returns the parsed envelope, asserting permitAll + success.
     *
     * <p>The term is passed as a URI TEMPLATE VARIABLE, never pre-encoded into the URL.
     * {@code RestTemplate}'s default {@code DefaultUriBuilderFactory} runs in
     * {@code TEMPLATE_AND_VALUES} mode, so handing it an already percent-encoded string
     * double-encodes it — «льв» arrives at the controller as the literal text
     * {@code %D0%BB%D1%8C%D0%B2}, which matches no settlement. Every Cyrillic assertion in this
     * class then fails, and the NEGATIVE ones (Мелітополь returns nothing) pass VACUOUSLY. Leave
     * the encoding to the template expansion.
     */
    private JsonNode getEnvelope(String query) throws Exception {
        ResponseEntity<String> response = query == null
                ? restTemplate.exchange(URL, HttpMethod.GET, anonymous(), String.class)
                : restTemplate.exchange(URL + "?query={q}", HttpMethod.GET, anonymous(),
                        String.class, query);

        assertThat(response.getStatusCode())
                .as("GET %s is permitAll (D7) — it is reached during registration, before a token "
                        + "exists, so it must answer 200 with no Authorization header", URL)
                .isEqualTo(HttpStatus.OK);

        JsonNode root = objectMapper.readTree(response.getBody());
        assertThat(root.path("success").asBoolean()).isTrue();
        return root;
    }

    private JsonNode getData(String query) throws Exception {
        return getEnvelope(query).path("data");
    }

    private static List<String> namesOf(JsonNode data) {
        List<String> names = new ArrayList<>();
        data.forEach(row -> names.add(row.path("nameUk").asText()));
        return names;
    }

    // ── Acceptance: the ranking ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("ranking (phase-326 acceptance)")
    class Ranking {

        @Test
        @DisplayName("«льв» returns Львів the CITY first, ahead of the two villages of that name")
        void should_rankLvivFirst_when_queryIsAThreeLetterPrefix() throws Exception {
            JsonNode data = getData("льв");

            assertThat(data).isNotEmpty();
            assertThat(data.get(0).path("nameUk").asText()).isEqualTo("Львів");
            assertThat(data.get(0).path("oblastNameUk").asText()).isEqualTo("Львівська");
            assertThat(data.get(0).path("settlementType").asText())
                    .as("«Львів» is also a village in Дніпропетровська and one in Миколаївська; "
                            + "the is_major tier is what puts the city on top of an exact tie")
                    .isEqualTo("CITY");
        }

        @Test
        @DisplayName("«іван фран» reaches Івано-Франківськ through the typo tier")
        void should_rankIvanoFrankivskFirst_when_queryIsAnAbbreviatedTwoWordTerm() throws Exception {
            JsonNode data = getData("іван фран");

            assertThat(data).isNotEmpty();
            assertThat(data.get(0).path("nameUk").asText())
                    .as("no settlement starts with «іван фран», so tier 1 is empty and the answer "
                            + "comes from trigram similarity. Raw similarity alone gets this WRONG "
                            + "— «Івано-Франкове» (Львівська) scores 0.500 against this term and "
                            + "«Івано-Франківськ» only 0.444 — which is why is_major is ranked "
                            + "above similarity rather than below it")
                    .isEqualTo("Івано-Франківськ");
        }

        @Test
        @DisplayName("«терноп» returns Тернопіль first, ahead of Тернопілля")
        void should_rankTernopilFirst_when_queryIsASixLetterPrefix() throws Exception {
            JsonNode data = getData("терноп");

            assertThat(data).isNotEmpty();
            assertThat(data.get(0).path("nameUk").asText()).isEqualTo("Тернопіль");
            assertThat(namesOf(data))
                    .as("Тернопілля (Львівська) is also a prefix match; the major city wins the tier")
                    .contains("Тернопілля");
        }

        @Test
        @DisplayName("an exactly-named village still beats a major city — the prefix tier dominates")
        void should_rankTheExactVillageFirst_when_queryPrefixesItButNotTheMajorCity()
                throws Exception {
            // The safety property behind ranking is_major above similarity: a major city can only
            // outrank a settlement in the SAME tier. «тернове» prefixes the villages and not
            // «Тернопіль», so no amount of is_major can promote the city over them.
            JsonNode data = getData("тернове");

            assertThat(data).isNotEmpty();
            assertThat(data.get(0).path("nameUk").asText()).isEqualTo("Тернове");
        }
    }

    // ── Acceptance: the three query states ────────────────────────────────────────────────────

    @Nested
    @DisplayName("query states (phase-326 D2/D3/D8)")
    class QueryStates {

        @Test
        @DisplayName("no query returns exactly the 50 curated major settlements")
        void should_returnTheFiftyMajorSettlements_when_queryIsAbsent() throws Exception {
            JsonNode data = getData(null);

            Integer majorRows = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM cities WHERE is_major", Integer.class);

            assertThat(majorRows)
                    .as("V171 ABORTS unless exactly 50 rows are major, so this is a migration "
                            + "invariant the endpoint must reproduce, not an estimate")
                    .isEqualTo(50);
            assertThat(data.size()).isEqualTo(50);
            assertThat(namesOf(data)).contains("Київ", "Львів", "Одеса", "Харків");
        }

        @Test
        @DisplayName("the pre-typing list is ordered by Ukrainian name")
        void should_orderMajorSettlementsByName_when_queryIsAbsent() throws Exception {
            List<String> served = namesOf(getData(null));

            // Compared against the DATABASE's own ordering rather than Java's String::compareTo:
            // the sort runs in PostgreSQL under the database collation, and Java's UTF-16 code
            // point order is a different order for Cyrillic. Asserting the Java order would pin
            // the wrong contract and could fail on data that is correctly sorted.
            List<String> expected = jdbcTemplate.queryForList(
                    "SELECT c.name_uk FROM cities c JOIN oblasts o ON o.id = c.oblast_id "
                            + "WHERE c.is_major ORDER BY c.name_uk, o.name_uk, c.id",
                    String.class);

            assertThat(served).containsExactlyElementsOf(expected);
        }

        @Test
        @DisplayName("a 2-character query returns an empty list plus the Ukrainian hint")
        void should_returnEmptyWithHint_when_queryIsTwoCharacters() throws Exception {
            JsonNode envelope = getEnvelope("ль");

            assertThat(envelope.path("data")).isEmpty();
            assertThat(envelope.path("message").asText()).isEqualTo("Введіть щонайменше 3 символи");
        }

        @Test
        @DisplayName("a too-short query never falls back to the major list")
        void should_notReturnTheMajorList_when_queryIsBelowTheMinimum() throws Exception {
            // The failure this guards is the one NormalizedSearchQuery was written for: collapsing
            // "nothing typed" and "typed but too short" into one state, so a 2-character keystroke
            // silently serves the unfiltered set.
            assertThat(getData("ль")).isEmpty();
            assertThat(getData(null)).hasSize(50);
        }

        @Test
        @DisplayName("a typed query is capped at 20 rows, whatever the candidate count")
        void should_capResultsAtTwenty_when_queryMatchesManySettlements() throws Exception {
            // 99 settlements are called «Іванівка»; «нов» matches 1 065 candidate rows.
            assertThat(getData("іванівка").size()).isEqualTo(MAX_RESULTS);
            assertThat(getData("нов").size()).isEqualTo(MAX_RESULTS);
        }
    }

    // ── Acceptance: disambiguation ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("duplicate names (phase-326 D5/D6)")
    class Disambiguation {

        @Test
        @DisplayName("the three «Львів» rows are distinct ids with distinct oblast labels")
        void should_returnDistinctRowsWithOblastLabels_when_theNameIsDuplicated() throws Exception {
            JsonNode data = getData("льв");

            List<String> ids = new ArrayList<>();
            List<String> lvivLabels = new ArrayList<>();
            data.forEach(row -> {
                ids.add(row.path("settlementId").asText());
                if ("Львів".equals(row.path("nameUk").asText())) {
                    lvivLabels.add(row.path("oblastNameUk").asText());
                }
            });

            assertThat(ids).doesNotHaveDuplicates();
            assertThat(lvivLabels)
                    .as("the client stores settlementId (D6), but a human picks by the label (D5) "
                            + "— three identically named rows must be told apart on screen")
                    .containsExactlyInAnyOrder("Львівська", "Дніпропетровська", "Миколаївська")
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("every row carries a settlementId and a non-blank oblast label")
        void should_carryIdAndLabelOnEveryRow_when_resultsAreReturned() throws Exception {
            JsonNode data = getData("іванівка");

            assertThat(data).isNotEmpty();
            data.forEach(row -> {
                assertThat(row.path("settlementId").asText()).isNotBlank();
                assertThat(row.path("oblastNameUk").asText()).isNotBlank();
                assertThat(row.path("nameUk").asText()).isEqualTo("Іванівка");
            });
        }
    }

    // ── Acceptance: the occupied-territory absence invariant ──────────────────────────────────

    @Nested
    @DisplayName("occupied settlements are unreachable (phase-325 D3 / phase-326 D4)")
    class OccupiedSettlements {

        @Test
        @DisplayName("«Мелітополь» returns no settlement of that name, at any query length")
        void should_returnNoOccupiedSettlement_when_queriedByFullOrPartialName() throws Exception {
            // Driven by the ACTUAL seeded data, not a fixture: the row does not exist because V171
            // never offered it, so the endpoint cannot return it. Partial terms DO return fuzzy
            // neighbours (Мелені, Мельна …) — that is correct behaviour and is why the assertion
            // is "no row named Мелітополь", not "no rows at all".
            // POSITIVE CONTROL FIRST. Three doesNotContain assertions over an empty list pass for
            // the wrong reason, and did exactly that on the first run of this class: a
            // pre-encoded URL made every Cyrillic term arrive as literal percent-escapes, so the
            // endpoint matched nothing and these three went green while every ranking test failed.
            // Proving the same request shape CAN return a Cyrillic match is what makes the
            // absence below mean something.
            assertThat(namesOf(getData("меліт")))
                    .as("if this is empty the negative assertions below are vacuous")
                    .isNotEmpty();

            assertThat(namesOf(getData("Мелітополь"))).doesNotContain("Мелітополь");
            assertThat(namesOf(getData("мелітопол"))).doesNotContain("Мелітополь");
            assertThat(namesOf(getData("меліт"))).doesNotContain("Мелітополь");

            Integer stored = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM cities WHERE name_uk = 'Мелітополь'", Integer.class);
            assertThat(stored)
                    .as("the guarantee is an ABSENCE invariant held at import, not a predicate "
                            + "this endpoint applies — there is no occupation_status column")
                    .isZero();
        }

        @Test
        @DisplayName("no settlement_type filter hides villages — 24 013 of them must be findable")
        void should_returnVillagesAndSettlements_when_theyMatch() throws Exception {
            JsonNode data = getData("іванівка");

            List<String> types = new ArrayList<>();
            data.forEach(row -> types.add(row.path("settlementType").asText()));

            assertThat(types)
                    .as("the retiring cascade is CITY-only; this endpoint exists precisely because "
                            + "the other 25 345 settlements must be reachable")
                    .contains("VILLAGE");
        }
    }

    // ── Input handling that only the real column can prove ────────────────────────────────────

    @Nested
    @DisplayName("input handling against the real stored names")
    class InputHandling {

        @Test
        @DisplayName("cities.name_uk stores U+2019, not U+0027 — the fold direction depends on it")
        void should_storeCurlyApostrophes_when_taxonomyIsImported() {
            Integer curly = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM cities WHERE name_uk LIKE '%’%'", Integer.class);
            Integer straight = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM cities WHERE name_uk LIKE '%''%'", Integer.class);

            assertThat(curly)
                    .as("SettlementSearchService folds query apostrophes ONTO U+2019, the opposite "
                            + "direction to NormalizedSearchQuery#foldApostrophes. If a future "
                            + "re-import switched to U+0027 this test must fail loudly, because "
                            + "the fold would then silently drop every apostrophe settlement from "
                            + "the prefix tier")
                    .isPositive();
            assertThat(straight).isZero();
        }

        @Test
        @DisplayName("a straight apostrophe finds a settlement stored with the curly one")
        void should_findApostropheSettlement_when_queryUsesTheStraightVariant() throws Exception {
            List<String> names = namesOf(getData("кам'янка"));

            assertThat(names)
                    .as("a desktop keyboard emits U+0027; the column holds U+2019")
                    .isNotEmpty()
                    .allMatch(name -> name.startsWith("Кам’янка"));
        }

        @Test
        @DisplayName("a term with no alphanumeric character never reaches the database at all")
        void should_notScan_when_queryHasNoAlphanumeric() throws Exception {
            // «•»x100 clears the 3-character floor, clears the @Size ceiling's predecessor and
            // clears the controller's Cc/Cf/Zl/Zp @Pattern (a bullet is Po, not a control
            // character) — and yields NO pg_trgm trigram, so the planner abandons
            // idx_cities_name_uk_trgm and evaluates similarity() on all 25 697 rows. Measured at
            // 115-119 ms against 3.4 ms for «нов»; 240 of those (one IP's full minute) saturated
            // the Hikari pool for 6.02 s on a permitAll endpoint. See
            // should_sequentialScanTheWholeTable_when_aZeroTrigramTermIsPlanned below for the plan
            // this input produces IF it is ever executed — the database cannot defend itself here,
            // which is why the guard is in SettlementSearchService.
            //
            // Asserted as STATEMENTS ISSUED, not as elapsed time: a latency assertion on a 25 698-row
            // table is a flake generator on CI, and "it was fast" is not the property. The property
            // is that nothing was planned or executed, which is also the only form of this assertion
            // that goes red when the guard is deleted.
            //
            // 50 bullets, not the 100 originally measured: the @Size ceiling was halved to 50 in
            // the same change, so a 100-character term is now rejected at the controller and would
            // make this test a 400 that proves nothing about the service. 50 is the longest
            // zero-trigram term that still reaches SettlementSearchService — i.e. exactly the
            // worst case this guard is the last defence against. The two controls compose; neither
            // is asserted through the other.
            Statistics statistics = HibernateStatistics.enabledOn(entityManagerFactory);
            long before = statistics.getPrepareStatementCount();

            JsonNode data = getData("•".repeat(50));

            assertThat(data)
                    .as("a term the trigram index cannot serve returns an explicit empty list — "
                            + "never the major list, never an unfiltered scan")
                    .isEmpty();
            assertThat(statistics.getPrepareStatementCount() - before)
                    .as("the request must issue ZERO JDBC statements: the zero-trigram guard runs "
                            + "before the repository call, so no plan is ever built and no Seq Scan "
                            + "on cities can occur. A non-zero count here means the guard is gone "
                            + "and this permitAll endpoint is a pool-saturation lever again")
                    .isZero();
        }

        @Test
        @DisplayName("a punctuation term that DID reach the database would scan every row")
        void should_sequentialScanTheWholeTable_when_aZeroTrigramTermIsPlanned() {
            // The counterpart to the test above, and deliberately NOT falsifiable by the Java
            // guard: it asserts a PostgreSQL property, which is the whole reason the guard cannot
            // live in SQL. Without it, a future reader could reasonably conclude the application
            // check is redundant with the GIN index and delete it.
            //
            // The predicate is restated rather than imported: V173SettlementTrigramIndexMigrationTest
            // owns the full production statement and its generic-plan probe. What is load-bearing
            // for THIS assertion is only the two-tier OR, and a copy of it is honest about that.
            jdbcTemplate.execute("ANALYZE cities");
            String adversarial = "•".repeat(100);

            List<String> plan = jdbcTemplate.queryForList("""
                    EXPLAIN SELECT c.id
                    FROM cities c
                    WHERE c.name_uk ILIKE ?
                       OR (c.name_uk % ? AND similarity(c.name_uk, ?) >= 0.3)
                    """, String.class,
                    adversarial + "%", adversarial, adversarial);

            assertThat(String.join("\n", plan))
                    .as("show_trgm('•••') is the empty set, so neither tier has an index key to "
                            + "look up and the planner falls back to a full scan — the cost the "
                            + "service-layer guard exists to make unreachable")
                    .contains("Seq Scan on cities");
        }

        @Test
        @DisplayName("a term padded out of 2-character tokens never reaches the database either")
        void should_notScan_when_everyTokenIsShorterThanTheTrigramFloor() throws Exception {
            // «ка »x17 — the second denial-of-service vector, asserted over real HTTP against the
            // real table. It is 50 characters (clears @Size), far longer than the 3-character floor
            // (clears that), and entirely alphanumeric-and-space (clears the zero-trigram guard the
            // FIRST vector produced). It still cost 51.7 ms against 10.9 ms for the worst benign
            // query, because the whole term yields three distinct pg_trgm keys and one match is
            // enough at a 0.3 threshold: 10 070 rows each paying a similarity() recheck.
            //
            // Asserted as STATEMENTS ISSUED for the same reason as the test above: an empty list is
            // equally consistent with "refused" and with "executed and matched nothing", and this
            // class already shipped one test that could not tell those apart.
            Statistics statistics = HibernateStatistics.enabledOn(entityManagerFactory);
            long before = statistics.getPrepareStatementCount();

            JsonNode envelope = getEnvelope("ка ".repeat(16) + "ка");

            assertThat(envelope.path("data")).isEmpty();
            assertThat(envelope.path("message").asText())
                    .as("refusing and telling the user why are ONE decision — while they were two "
                            + "predicates, an unservable term returned a silent empty list")
                    .isEqualTo("Введіть щонайменше 3 символи");
            assertThat(statistics.getPrepareStatementCount() - before)
                    .as("zero JDBC statements: the trigram-run guard runs before the repository "
                            + "call, so no plan is built and no recheck over 10 070 rows can occur")
                    .isZero();
        }

        @Test
        @DisplayName("a typed LIKE wildcard is a literal, not a match-everything")
        void should_treatUnderscoreAsLiteral_when_queryContainsALikeWildcard() throws Exception {
            // "___" is the URL-safe half of the LIKE metacharacter pair (a raw "%" cannot be put
            // through the RestTemplate URI template without double-encoding, and both characters
            // go through the same escape, which SettlementSearchServiceTest pins character by
            // character). Unescaped, ILIKE '___%' matches every settlement of three or more
            // characters — i.e. the whole table lands in the prefix tier and the ranking becomes
            // meaningless.
            //
            // HONEST LIMIT, recorded rather than left implied: since the admission guard became a
            // 3-character alphanumeric RUN, "___" is refused before the escape runs, so this case
            // proves the REFUSAL and no longer proves the escaping. The escaping is pinned
            // character by character in SettlementSearchServiceTest and reaches the database only
            // for terms that also carry a real run — «abc%def_ghi\jkl» there. Keeping this case is
            // still worth it: it is the shortest input that walks the first two controls.
            assertThat(getData("___")).isEmpty();
            assertThat(getData("льв")).isNotEmpty();
        }
    }

    // ── Caching ───────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("caching")
    class Caching {

        @Test
        @DisplayName("the major list is cached; the @Lazy self-proxy is what makes that true")
        @SuppressWarnings("unchecked")
        void should_populateTheMajorCache_when_theBlankQueryIsServed() throws Exception {
            assertThat(cacheManager.getCacheNames())
                    .as("CacheConfig runs with dynamic cache creation OFF, so an unregistered name "
                            + "would throw rather than mint an unbounded cache — but a RENAMED one "
                            + "would silently stop being asserted here")
                    .contains("settlementMajors");
            cacheManager.getCache("settlementMajors").clear();

            getData(null);

            Cache<Object, Object> nativeCache =
                    (Cache<Object, Object>) cacheManager.getCache("settlementMajors").getNativeCache();

            assertThat(nativeCache.estimatedSize())
                    .as("a direct this.listMajorSettlements() call would bypass the AOP proxy and "
                            + "leave this cache permanently empty (§F-3), with nothing else "
                            + "failing to say so")
                    .isEqualTo(1L);
        }

        /**
         * The blank query IS cached, proven by statement count — and it is here to keep its sibling
         * honest. A "no statement was saved" assertion is only meaningful if the same probe can also
         * register a save; without this contrast, a probe that silently counted nothing would let
         * {@link #should_queryTheDatabaseEveryTime_when_theSameTermIsSearchedTwice()} pass for the
         * wrong reason.
         */
        @Test
        @DisplayName("a repeated blank query is served from the cache — zero statements")
        void should_issueNoStatement_when_theMajorListIsRepeated() throws Exception {
            getData(null);
            Statistics statistics = HibernateStatistics.enabledOn(entityManagerFactory);
            long before = statistics.getPrepareStatementCount();

            getData(null);
            getData(null);

            assertThat(statistics.getPrepareStatementCount() - before)
                    .as("settlementMajors is a single fixed key over Flyway-seed data, so the "
                            + "second and third calls must be answered without touching the DB")
                    .isZero();
        }

        @Test
        @DisplayName("per-query results are NOT cached — the same term queries the DB every time")
        void should_queryTheDatabaseEveryTime_when_theSameTermIsSearchedTwice() throws Exception {
            // REWRITTEN from an assertion that three cache NAMES were absent. That test could only
            // fail if someone introduced a cache spelled exactly "settlementSearch", "settlements"
            // or "settlementQuery" — a spelling coincidence, not the property. A per-query
            // @Cacheable added under any fourth name, which is the actual regression (§A: a Caffeine
            // cache keyed on caller text behind a permitAll endpoint is slot-exhaustible by an
            // anonymous caller), would have sailed past it green.
            //
            // The property itself is observable: if the result were cached, the repeat calls would
            // issue no statement. The first call is made outside the measurement window so that
            // statement-cache/plan warm-up cannot be mistaken for a result cache.
            getData("льв");
            Statistics statistics = HibernateStatistics.enabledOn(entityManagerFactory);
            long before = statistics.getPrepareStatementCount();

            getData("льв");
            getData("льв");

            assertThat(statistics.getPrepareStatementCount() - before)
                    .as("two repeats of an already-seen term must cost two statements. One (or "
                            + "zero) means a per-query result cache appeared, and its key space is "
                            + "every prefix an anonymous caller can type")
                    .isEqualTo(2);
        }
    }
}
