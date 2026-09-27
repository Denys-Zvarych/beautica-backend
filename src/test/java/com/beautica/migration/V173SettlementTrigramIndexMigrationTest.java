package com.beautica.migration;

import com.beautica.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test for {@code V173__index_cities_name_uk_trgm} — the GIN {@code gin_trgm_ops} index
 * behind the Phase 326 settlement autocomplete.
 *
 * <p><b>Why a plan test and not a latency test.</b> 25 698 rows fit comfortably in shared buffers,
 * so a sequential scan of the whole table completes in ~28 ms warm — fast enough to satisfy any
 * reasonable latency assertion while the index it was supposed to prove sits unused. The phase doc
 * says so explicitly, and memory {@code project_hibernate_batch_fetch_staircase} records the same
 * class of mistake: a measurement under a threshold is a FLOOR, never the shape. So this class
 * asserts the PLAN — that both ranking tiers reach {@code idx_cities_name_uk_trgm} and that
 * neither degrades to {@code Seq Scan}.
 *
 * <p><b>And it asserts it under a GENERIC plan.</b> The application binds the pattern and the term
 * as JDBC parameters, and pgJDBC promotes a statement to a server-side prepare after five
 * executions, at which point PostgreSQL may build a plan with no knowledge of the values. An index
 * whose use depended on a literal pattern would silently fall back to that 28 ms scan on the sixth
 * keystroke of a session and nothing would report it. {@code force_generic_plan} reproduces that
 * state deterministically.
 *
 * <p>Read-only: the settlement taxonomy is permanent reference data that
 * {@link AbstractIntegrationTest#cleanDb()} deliberately does not truncate, so every test here is
 * order-independent and seeds nothing.
 */
@DisplayName("V173 migration — pg_trgm GIN index for the settlement autocomplete")
class V173SettlementTrigramIndexMigrationTest extends AbstractIntegrationTest {

    private static final String INDEX_NAME = "idx_cities_name_uk_trgm";

    /** Session-scoped name for the PREPAREd statement the plan assertions explain. */
    private static final String PREPARED_NAME = "v173_settlement_search_plan_probe";

    /**
     * The production query's shape, as a PREPAREable statement with the repository's binds
     * expressed as {@code $n} parameters. Kept in sync with {@code CityRepository#searchByName}
     * by {@code SettlementSearchIT}, which asserts the shipped repository method produces the
     * same first row as this SQL.
     *
     * <p>{@code $1} = the escaped prefix pattern, {@code $2} = the bare term, {@code $3} = the
     * explicit similarity floor.
     */
    private static final String SEARCH_SQL = """
            SELECT c.id
            FROM cities c
            JOIN oblasts o ON o.id = c.oblast_id
            WHERE c.name_uk ILIKE $1
               OR (c.name_uk % $2 AND similarity(c.name_uk, $2) >= $3)
            ORDER BY (CASE WHEN c.name_uk ILIKE $1 THEN 0 ELSE 1 END),
                     c.is_major DESC,
                     similarity(c.name_uk, $2) DESC,
                     length(c.name_uk),
                     c.name_uk,
                     o.name_uk,
                     c.id
            LIMIT 20
            """;

    /**
     * Explains the production query for {@code term} under a GENERIC plan.
     *
     * <p><b>Why server-side {@code PREPARE}/{@code EXECUTE} rather than JDBC {@code ?} binds.</b>
     * {@code plan_cache_mode} governs how a CACHED plan is chosen, and a one-shot JDBC statement
     * may never become one — so binding through the driver could quietly measure a custom plan and
     * report a pass for the case this test exists to falsify. An explicit {@code PREPARE} is
     * unambiguously a cached plan, and {@code force_generic_plan} then guarantees the parameterised
     * form. The resulting plan prints {@code $1}/{@code $2} in its index conditions, which is the
     * visible proof that the planner had no values to work with and chose the index anyway.
     *
     * <p>Everything runs on ONE connection so the {@code ANALYZE}, the GUC and the prepared
     * statement all apply to the same session, and the statement is deallocated and the GUC reset
     * before the pooled connection goes back.
     *
     * <p>{@code ANALYZE cities} runs first because a freshly migrated container has no statistics
     * for the 25 697 rows V171 just inserted; production gets them from autovacuum within minutes
     * of the deploy, so planning WITHOUT them would measure a state the application never runs in.
     */
    private List<String> explainSearch(String term) {
        String literal = "'" + term.replace("'", "''") + "'";
        return jdbcTemplate.execute((ConnectionCallback<List<String>>) connection -> {
            List<String> plan = new ArrayList<>();
            try (Statement statement = connection.createStatement()) {
                statement.execute("ANALYZE cities");
                statement.execute("SET plan_cache_mode = force_generic_plan");
                statement.execute("PREPARE " + PREPARED_NAME
                        + "(text, text, real) AS " + SEARCH_SQL);
                try (ResultSet rs = statement.executeQuery(
                        "EXPLAIN (ANALYZE, BUFFERS) EXECUTE " + PREPARED_NAME
                                + "(" + literal + " || '%', " + literal + ", 0.3)")) {
                    while (rs.next()) {
                        plan.add(rs.getString(1));
                    }
                }
            } finally {
                try (Statement cleanup = connection.createStatement()) {
                    cleanup.execute("DEALLOCATE ALL");
                    cleanup.execute("RESET plan_cache_mode");
                }
            }
            return plan;
        });
    }

    @Nested
    @DisplayName("the index itself")
    class IndexDefinition {

        @Test
        @DisplayName("V173 creates a GIN gin_trgm_ops index on cities.name_uk")
        void should_createGinTrigramIndexOnNameUk_when_v173Applied() {
            String indexDef = jdbcTemplate.queryForObject(
                    "SELECT indexdef FROM pg_indexes WHERE tablename = 'cities' AND indexname = ?",
                    String.class, INDEX_NAME);

            assertThat(indexDef)
                    .as("the access method and the opclass are BOTH load-bearing: a plain B-tree "
                            + "on name_uk would satisfy ddl-auto=validate and serve neither the "
                            + "ILIKE prefix tier nor the %% similarity tier")
                    .contains("USING gin")
                    .contains("gin_trgm_ops")
                    .contains("name_uk");
        }

        @Test
        @DisplayName("the index is NOT partial — the autocomplete searches every settlement type")
        void should_coverEveryRow_when_indexIsNotPartial() {
            String indexDef = jdbcTemplate.queryForObject(
                    "SELECT indexdef FROM pg_indexes WHERE tablename = 'cities' AND indexname = ?",
                    String.class, INDEX_NAME);

            assertThat(indexDef)
                    .as("villages are 24 013 of the 25 698 rows and are exactly what Phase 326 "
                            + "exists to make findable — a WHERE clause here would hide them")
                    .doesNotContain(" WHERE ");
        }

        @Test
        @DisplayName("pg_trgm is installed — the opclass cannot exist without it")
        void should_haveTrigramExtensionInstalled_when_v173Applied() {
            Integer installed = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM pg_extension WHERE extname = 'pg_trgm'", Integer.class);

            assertThat(installed).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("the plan the shipped query actually gets")
    class QueryPlan {

        @Test
        @DisplayName("a 3-character prefix reaches the GIN index, never a sequential scan")
        void should_useTrigramIndex_when_queryIsAThreeCharacterPrefix() {
            String plan = String.join("\n", explainSearch("льв"));

            assertThat(plan)
                    .as("plan was:%n%s", plan)
                    .contains(INDEX_NAME)
                    .doesNotContain("Seq Scan on cities");
        }

        @Test
        @DisplayName("a multi-word typo query reaches the same index through the % operator")
        void should_useTrigramIndex_when_queryOnlyMatchesThroughSimilarity() {
            String plan = String.join("\n", explainSearch("іван фран"));

            assertThat(plan)
                    .as("plan was:%n%s", plan)
                    .contains(INDEX_NAME)
                    .doesNotContain("Seq Scan on cities");
        }

        @Test
        @DisplayName("ONE index serves BOTH tiers — the OR resolves to a BitmapOr over it")
        void should_serveBothTiersFromOneIndex_when_bothPredicatesArePresent() {
            List<String> plan = explainSearch("терноп");
            String joined = String.join("\n", plan);

            long scansOfThisIndex = plan.stream()
                    .filter(line -> line.contains(INDEX_NAME))
                    .count();

            assertThat(joined)
                    .as("plan was:%n%s", joined)
                    .contains("BitmapOr");
            assertThat(scansOfThisIndex)
                    .as("D1 claims one index serves the prefix tier AND the similarity tier; two "
                            + "Bitmap Index Scans of the SAME index under one BitmapOr is what "
                            + "that looks like. Plan was:%n%s", joined)
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("the worst measured 3-character query is still index-served")
        void should_useTrigramIndex_when_queryMatchesOverAThousandCandidates() {
            // «нов» is the widest 3-character term in the real taxonomy (1 065 candidate rows —
            // Новоселівка, Нова Гребля, Новий Світ …). If any keystroke were going to fall back
            // to a sequential scan, it is this one.
            String plan = String.join("\n", explainSearch("нов"));

            assertThat(plan)
                    .as("plan was:%n%s", plan)
                    .contains(INDEX_NAME)
                    .doesNotContain("Seq Scan on cities");
        }
    }

    @Nested
    @DisplayName("the environment assumption the similarity tier rests on")
    class TrigramThreshold {

        @Test
        @DisplayName("pg_trgm.similarity_threshold is 0.3 — the floor the % operator applies")
        void should_useTheDefaultSimilarityThreshold_when_sessionIsUnmodified() {
            String threshold = jdbcTemplate.queryForObject(
                    "SHOW pg_trgm.similarity_threshold", String.class);

            assertThat(Double.parseDouble(threshold))
                    .as("the indexable %% operator compares against this SESSION GUC, and the "
                            + "repository binds the same value explicitly. A LOWERED GUC is "
                            + "neutralised by that explicit floor; a RAISED one would silently "
                            + "narrow the typo tier, which is why it is asserted rather than assumed")
                    .isEqualTo(0.3d);
        }
    }
}
