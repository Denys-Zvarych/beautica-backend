package com.beautica.migration;

import com.beautica.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract test for {@code V174__add_cities_hromada_disambiguation} and its data half
 * {@code V175__backfill_settlement_hromadas} — Phase 327's hromada columns on {@code cities}.
 *
 * <p><b>Why the CHECK is exercised with raw SQL and not through a fixture.</b> Nothing in the
 * application can write {@code cities}: the table is Flyway-seed reference data with no repository
 * write method, so no service call and no test fixture can construct the state
 * {@code chk_cities_hromada_disambiguates} exists to forbid. A constraint reached by no test is a
 * comment with a syntax highlighter. {@link CheckConstraint} therefore issues the forbidden
 * {@code INSERT} directly and requires PostgreSQL to reject it, and pairs every rejection with a
 * POSITIVE control so "the statement failed" cannot be mistaken for "the constraint fired".
 *
 * <p><b>And it is deliberately not the inert shape from
 * {@code project_postgres_check_null_passes}.</b> That memory records a CHECK made silently vacuous
 * by a NULLable column inside an OR chain: PostgreSQL admits a row whose CHECK evaluates to NULL,
 * so {@code NULL OR FALSE} passes. Here {@code ambiguous_in_oblast} is {@code NOT NULL}, which is
 * what makes {@code NOT ambiguous_in_oblast OR hromada_name_uk IS NOT NULL} two-valued and the
 * constraint real. {@link ColumnShape} pins that nullability, because it is the whole reason the
 * constraint can fire.
 *
 * <p>Read-only otherwise: the settlement taxonomy is permanent reference data that
 * {@link AbstractIntegrationTest#cleanDb()} deliberately does not truncate, so the counts below are
 * order-independent. Every row this class inserts is removed by KATOTTH code in the same test.
 */
@DisplayName("V174/V175 migration — hromada columns, their CHECK, and the loaded data")
class V174CitiesHromadaDisambiguationMigrationTest extends AbstractIntegrationTest {

    private static final String CONSTRAINT = "chk_cities_hromada_disambiguates";

    /** KATOTTH code of the probe row. Outside the classifier's numbering, so it collides with none. */
    private static final String PROBE_CODE = "UA00000000000000001";

    /** Kyiv — category K, not a level-4 settlement, and therefore not in either CSV. */
    private static final String KYIV_CODE = "UA80000000000093317";

    private static final String INSERT_SQL = """
            INSERT INTO cities (oblast_id, katotth_code, name_uk, name_en,
                                settlement_type, is_major, hromada_name_uk, ambiguous_in_oblast)
            VALUES (?, ?, 'Пробне', 'Probne', 'VILLAGE', FALSE, ?, ?)
            """;

    private UUID anyOblastId() {
        return jdbcTemplate.queryForObject("SELECT id FROM oblasts ORDER BY katotth_code LIMIT 1",
                UUID.class);
    }

    private void insertProbe(String hromada, boolean ambiguous) {
        jdbcTemplate.update(INSERT_SQL, anyOblastId(), PROBE_CODE, hromada, ambiguous);
    }

    private void deleteProbe() {
        jdbcTemplate.update("DELETE FROM cities WHERE katotth_code = ?", PROBE_CODE);
    }

    // ── The columns themselves ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("the two columns V174 adds")
    class ColumnShape {

        @Test
        @DisplayName("hromada_name_uk is a NULLABLE varchar(255) — three cities genuinely have none")
        void should_addNullableHromadaColumn_when_v174Applied() {
            Map<String, Object> column = jdbcTemplate.queryForMap("""
                    SELECT data_type, is_nullable, character_maximum_length
                      FROM information_schema.columns
                     WHERE table_name = 'cities' AND column_name = 'hromada_name_uk'
                    """);

            assertThat(column)
                    .as("Kyiv (KATOTTH category K) and the two exclusion-zone cities Прип'ять and "
                            + "Чорнобиль have no category-H parent — NOT NULL would have to be "
                            + "filled with a falsehood for them (phase-327 D5)")
                    .containsEntry("data_type", "character varying")
                    .containsEntry("is_nullable", "YES")
                    .containsEntry("character_maximum_length", 255);
        }

        @Test
        @DisplayName("ambiguous_in_oblast is NOT NULL — which is what keeps the CHECK two-valued")
        void should_addNotNullAmbiguityFlag_when_v174Applied() {
            Map<String, Object> column = jdbcTemplate.queryForMap("""
                    SELECT data_type, is_nullable, column_default
                      FROM information_schema.columns
                     WHERE table_name = 'cities' AND column_name = 'ambiguous_in_oblast'
                    """);

            assertThat(column)
                    .as("NOT NULL is load-bearing, not tidiness: a nullable flag would make "
                            + "`NOT ambiguous_in_oblast OR hromada_name_uk IS NOT NULL` evaluate to "
                            + "NULL, and PostgreSQL ADMITS a row whose CHECK is NULL — the exact "
                            + "inert shape recorded in project_postgres_check_null_passes")
                    .containsEntry("data_type", "boolean")
                    .containsEntry("is_nullable", "NO");
            assertThat((String) column.get("column_default"))
                    .as("the DEFAULT is what lets the column be added to a populated table before "
                            + "the constraint exists, and it is what Kyiv keeps")
                    .isEqualTo("false");
        }

        @Test
        @DisplayName("the CHECK constraint exists on cities with the conditional expression")
        void should_createConditionalCheckConstraint_when_v174Applied() {
            String definition = jdbcTemplate.queryForObject("""
                    SELECT pg_get_constraintdef(c.oid)
                      FROM pg_constraint c
                      JOIN pg_class t ON t.oid = c.conrelid
                     WHERE t.relname = 'cities' AND c.conname = ?
                    """, String.class, CONSTRAINT);

            assertThat(definition)
                    .isNotNull()
                    .contains("ambiguous_in_oblast")
                    .contains("hromada_name_uk IS NOT NULL");
        }
    }

    // ── The CHECK actually rejecting something ───────────────────────────────────────────────

    @Nested
    @DisplayName("the CHECK genuinely fires (phase-327 acceptance)")
    class CheckConstraint {

        @Test
        @DisplayName("an ambiguous row with a NULL hromada is REJECTED by the database")
        void should_rejectInsert_when_rowIsAmbiguousAndCarriesNoHromada() {
            assertThatThrownBy(() -> insertProbe(null, true))
                    .as("this is the broken state the phase exists to make unrepresentable: a row "
                            + "the oblast label cannot identify, with nothing further to identify "
                            + "it by. If this INSERT succeeds, the constraint is decorative.")
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining(CONSTRAINT);

            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM cities WHERE katotth_code = ?", Long.class, PROBE_CODE))
                    .as("the rejected row must not be half-written")
                    .isZero();
        }

        @Test
        @DisplayName("the SAME row with a hromada is accepted — the rejection was the constraint")
        void should_acceptInsert_when_theAmbiguousRowCarriesAHromada() {
            try {
                assertThatCode(() -> insertProbe("Пробненська", true))
                        .as("positive control: identical statement, one field changed. Without it "
                                + "the rejection above could be any of the five other constraints "
                                + "on this table and the test would still be green.")
                        .doesNotThrowAnyException();

                assertThat(jdbcTemplate.queryForObject(
                        "SELECT hromada_name_uk FROM cities WHERE katotth_code = ?",
                        String.class, PROBE_CODE))
                        .isEqualTo("Пробненська");
            } finally {
                deleteProbe();
            }
        }

        @Test
        @DisplayName("a NULL hromada is fine while the row is unambiguous — the CHECK is conditional")
        void should_acceptInsert_when_rowIsUnambiguousAndCarriesNoHromada() {
            try {
                assertThatCode(() -> insertProbe(null, false))
                        .as("76 % of rows are exactly this shape; a blanket NOT NULL would reject "
                                + "them and a blanket CHECK would be the wrong constraint")
                        .doesNotThrowAnyException();
            } finally {
                deleteProbe();
            }
        }

        @Test
        @DisplayName("clearing the hromada of an already-ambiguous row is rejected too")
        void should_rejectUpdate_when_anAmbiguousRowLosesItsHromada() {
            try {
                insertProbe("Пробненська", true);

                assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE cities SET hromada_name_uk = NULL WHERE katotth_code = ?",
                        PROBE_CODE))
                        .as("the constraint guards the row's whole lifetime, not only its INSERT — "
                                + "a future migration that blanked the column would be stopped here")
                        .isInstanceOf(DataIntegrityViolationException.class)
                        .hasMessageContaining(CONSTRAINT);
            } finally {
                deleteProbe();
            }
        }

        @Test
        @DisplayName("flagging an existing hromada-less row as ambiguous is rejected too")
        void should_rejectUpdate_when_aHromadaLessRowIsFlaggedAmbiguous() {
            assertThatThrownBy(() -> jdbcTemplate.update(
                    "UPDATE cities SET ambiguous_in_oblast = TRUE WHERE katotth_code = ?",
                    KYIV_CODE))
                    .as("Kyiv has no hromada, so it can never be flagged ambiguous — the other "
                            + "direction through the same constraint, on a REAL row")
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining(CONSTRAINT);
        }
    }

    // ── What V175 actually loaded ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("the data V175 backfilled")
    class LoadedData {

        @Test
        @DisplayName("6 103 of 25 698 rows are ambiguous — 76.25 % need no hromada at all")
        void should_flagExactlyTheCollidingRows_when_v175Applied() {
            long ambiguous = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM cities WHERE ambiguous_in_oblast", Long.class);
            long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cities", Long.class);

            assertThat(ambiguous).isEqualTo(6_103L);
            assertThat(total).isEqualTo(25_698L);
        }

        @Test
        @DisplayName("the flag agrees with the table — it is derived data, not an editorial list")
        void should_matchASelfJoinOverTheTable_when_ambiguityIsRecomputed() {
            // The flag is precomputed by the generator (phase-327 D2) precisely so the read path
            // never pays for this query. Running it ONCE here is what proves the precomputation
            // was not merely plausible: every flagged row must have a namesake in its oblast, and
            // no unflagged row may have one.
            List<Map<String, Object>> disagreements = jdbcTemplate.queryForList("""
                    SELECT katotth_code, name_uk, ambiguous_in_oblast
                      FROM (SELECT katotth_code, name_uk, ambiguous_in_oblast,
                                   COUNT(*) OVER (PARTITION BY name_uk, oblast_id) AS namesakes
                              FROM cities) t
                     WHERE ambiguous_in_oblast <> (namesakes > 1)
                     LIMIT 10
                    """);

            assertThat(disagreements)
                    .as("a stale flag is invisible at read time: the label would simply be missing "
                            + "a part, or carry one it does not need")
                    .isEmpty();
        }

        @Test
        @DisplayName("every settlement but the three hromada-less ones carries a hromada")
        void should_labelEveryRowWithAHromada_exceptTheThreeThatHaveNone() {
            List<String> without = jdbcTemplate.queryForList(
                    "SELECT name_uk FROM cities WHERE hromada_name_uk IS NULL ORDER BY name_uk",
                    String.class);

            assertThat(without)
                    .as("Kyiv is KATOTTH category K (an oblast-equivalent, absent from both CSVs); "
                            + "Прип'ять and Чорнобиль are level-4 cities whose level_3 parent is "
                            + "the Київська OBLAST rather than a hromada. Emitting «Київська» as "
                            + "their hromada would state a falsehood the user reads as a place.")
                    .containsExactlyInAnyOrder("Київ", "Прип’ять", "Чорнобиль");
        }

        @Test
        @DisplayName("Київ resolves with a null hromada and is CHECK-clean (phase-327 acceptance)")
        void should_leaveKyivUnflaggedWithNoHromada_when_v175Applied() {
            Map<String, Object> kyiv = jdbcTemplate.queryForMap(
                    "SELECT name_uk, hromada_name_uk, ambiguous_in_oblast FROM cities "
                            + "WHERE katotth_code = ?", KYIV_CODE);

            assertThat(kyiv)
                    .containsEntry("name_uk", "Київ")
                    .containsEntry("hromada_name_uk", null)
                    .containsEntry("ambiguous_in_oblast", false);
        }

        @Test
        @DisplayName("hromada names are the BARE adjective, never the full «... громада» (D4)")
        void should_storeTheBareAdjective_when_hromadaIsLoaded() {
            long verbose = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM cities WHERE hromada_name_uk ILIKE '%громада%'",
                    Long.class);

            assertThat(verbose)
                    .as("the server stores «Шишацька» and the client appends the noun, exactly as "
                            + "it does for «Полтавська» — the grammatical form depends on where "
                            + "the label is shown, which only the client knows")
                    .isZero();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT MAX(length(hromada_name_uk)) FROM cities", Integer.class))
                    .isLessThanOrEqualTo(255);
        }

        @Test
        @DisplayName("«Іванівка, Дніпропетровська» ×10 — 9 hromadas, not 10 (phase-327 D6)")
        void should_separateMostIvanivkas_when_theHromadaIsAdded() {
            List<String> hromadas = jdbcTemplate.queryForList("""
                    SELECT c.hromada_name_uk
                      FROM cities c JOIN oblasts o ON o.id = c.oblast_id
                     WHERE c.name_uk = 'Іванівка' AND o.name_uk = 'Дніпропетровська'
                     ORDER BY c.hromada_name_uk
                    """, String.class);

            assertThat(hromadas).hasSize(10).doesNotContainNull();
            assertThat(hromadas.stream().distinct().count())
                    .as("the phase doc's acceptance says «every Дніпропетровська entry carries a "
                            + "DISTINCT hromada». Measured, it is 9 of 10: two Іванівка sit in "
                            + "Лозуватська hromada. That is D6's accepted residue, not a defect — "
                            + "KATOTTH exposes no level below the settlement. The number is pinned "
                            + "here so the claim stays the measured one.")
                    .isEqualTo(9L);
        }

        @Test
        @DisplayName("«Миколаївка, Харківська» ×15 — the worst group — is mostly told apart now")
        void should_reduceTheWorstGroup_when_theHromadaIsAdded() {
            List<String> hromadas = jdbcTemplate.queryForList("""
                    SELECT c.hromada_name_uk
                      FROM cities c JOIN oblasts o ON o.id = c.oblast_id
                     WHERE c.name_uk = 'Миколаївка' AND o.name_uk = 'Харківська'
                    """, String.class);

            assertThat(hromadas).hasSize(15).doesNotContainNull();
            assertThat(hromadas.stream().distinct().count())
                    .as("11 of 15 — NOT 15. Phase 327 D6 accepts a residue and this is part of it: "
                            + "three Миколаївка sit in Лозівська hromada and two each in "
                            + "Сахновщинська and Чкаловська. KATOTTH exposes no level below the "
                            + "settlement, so there is nothing further to add. Do not re-raise.")
                    .isEqualTo(11L);
        }

        @Test
        @DisplayName("226 rows stay ambiguous even with the hromada, and that is accepted (D6)")
        void should_leaveTheAcceptedResidue_when_labelsAreComposed() {
            // Measured over the LABEL the user reads — name + oblast + hromada NAME. Grouping by
            // the hromada CODE instead reports 212, because seven pairs sit in distinct hromadas
            // that happen to share a name; the user cannot see a code, so the honest number is the
            // one below. The phase doc quotes 212.
            Long residue = jdbcTemplate.queryForObject("""
                    SELECT COALESCE(SUM(n), 0) FROM (
                        SELECT COUNT(*) AS n
                          FROM cities c JOIN oblasts o ON o.id = c.oblast_id
                         GROUP BY c.name_uk, o.name_uk, c.hromada_name_uk
                        HAVING COUNT(*) > 1) g
                    """, Long.class);

            assertThat(residue)
                    .as("0.88 percent of 25 698, down from 23.75. The raion would leave 9.72 for "
                            + "the same cost and hromada is a SUBSET of raion (D1), so there is no "
                            + "cheaper level to add — only no level at all.")
                    .isEqualTo(226L);
        }
    }
}
