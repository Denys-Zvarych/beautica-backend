package db.migration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.io.InputStream;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for {@link V175__backfill_settlement_hromadas}: the guards that make the hromada
 * backfill safe and that no green migration run ever reaches, plus the one step a green run DOES
 * take whose correctness is invisible afterwards — the trigram index rebuild's statement order.
 *
 * <p><b>Why this class exists, and why it is not a Testcontainers test.</b>
 * {@code V174CitiesHromadaDisambiguationMigrationTest} proves what a SUCCESSFUL V175 leaves in
 * {@code cities}. It cannot prove anything about what V175 does with a bad input, because a bad
 * input aborts the migration and therefore the Spring context — every test in that class errors and
 * none of them reports the guard. Every branch here is reached before any SQL executes, is a pure
 * function of the classpath resource, or is a statement issued against a mock {@link Connection}, so
 * a Postgres container would be minutes of startup to assert an {@code if} (§M-1).
 *
 * <p><b>One thing here is NOT a failure branch</b>, and it is flagged rather than hidden:
 * {@link TrigramIndexRebuild} pins the SQL {@code restoreTrigramIndexHealth} issues and the ORDER it
 * issues it in. That is closer to implementation than the rest of this class, and it is here because
 * the order is load-bearing (V175's javadoc: ANALYZE over a still-bloated index is WORSE than
 * neither statement) and unobservable from any post-migration state. The behavioural half —
 * that the rebuild actually happened — lives in {@code SettlementSearchCostGuardIT
 * .should_keepTheTrigramIndexCompact_when_theMigrationsHaveApplied}, which measures the index. The
 * two are complements: deleting the CALL from {@code migrate} fails only the IT, swapping the two
 * statements fails only this class.
 *
 * <p>Package {@code db.migration} mirrors the class under test so the package-private seams
 * documented in its Javadoc are reachable without reflection. The structure deliberately mirrors
 * {@link V171ImportFreeSettlementsTest}, because the two migrations carry the same strict reader for
 * the same reason and a reader that drifted from its test would be the way that stopped being true.
 */
@DisplayName("V175__backfill_settlement_hromadas — its guards, and the index rebuild it owes")
class V175BackfillSettlementHromadasTest {

    private final V175__backfill_settlement_hromadas migration =
            new V175__backfill_settlement_hromadas();

    // -----------------------------------------------------------------------
    // Malformed CSV — splitQuoted / toRow
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("malformed CSV is rejected, never guessed at")
    class MalformedCsv {

        @Test
        @DisplayName("an unquoted field aborts — the reader is QUOTE_ALL-strict by design")
        void should_abort_when_aFieldIsNotQuoted() {
            assertThatThrownBy(() -> migration.splitQuoted("\"UA1\",Шишацька", 7))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("V175 blocked: malformed")
                    .hasMessageContaining("line 7")
                    .hasMessageContaining("field 2 is not quoted");
        }

        @Test
        @DisplayName("an unterminated quoted field aborts rather than silently truncating the row")
        void should_abort_when_aQuotedFieldIsUnterminated() {
            assertThatThrownBy(() -> migration.splitQuoted("\"UA1\",\"Шишацька", 12))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("unterminated quoted field");
        }

        @Test
        @DisplayName("a missing delimiter between two quoted fields aborts")
        void should_abort_when_aDelimiterIsMissingBetweenFields() {
            assertThatThrownBy(() -> migration.splitQuoted("\"UA1\";\"UA2\"", 3))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("expected ',' after a quoted field");
        }

        @Test
        @DisplayName("the wrong column count aborts — a re-shaped CSV must not load partially")
        void should_abort_when_theRowHasTheWrongColumnCount() {
            assertThatThrownBy(() -> migration.toRow(List.of("UA1", "Шишацька"), 42))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("expected 3 columns, got 2");
        }

        @Test
        @DisplayName("a token that is neither \"true\" nor \"false\" aborts instead of meaning false")
        void should_abort_when_theAmbiguityFlagIsNeitherTrueNorFalse() {
            // Boolean.parseBoolean is total: every token that is not "true" reads as false. The
            // two post-conditions in verifyFinalState are TOTALS, so a pair of hand-edits nets out
            // — "true"->"ture" on one row plus "false"->"true" on another still lands 6 103
            // ambiguous rows and still passes. The first settlement then loses its disambiguator
            // with no error anywhere, which is precisely the ambiguity this phase removes.
            assertThatThrownBy(() -> migration.toRow(List.of("UA1", "Шишацька", "ture"), 8))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("V175 blocked: malformed")
                    .hasMessageContaining("line 8")
                    .hasMessageContaining("ambiguous_in_oblast is \"ture\"");
        }

        @Test
        @DisplayName("the flag is case-SENSITIVE — the generator writes lowercase and only that")
        void should_abort_when_theAmbiguityFlagIsCapitalised() {
            // Not pedantry: Boolean.parseBoolean accepts "TRUE" case-insensitively while treating
            // "FALSE" and "Fasle" identically, so a reader that tolerated one casing would still be
            // silently lenient on the other side. One accepted spelling per value, or the asymmetry
            // is back.
            assertThatThrownBy(() -> migration.toRow(List.of("UA1", "Шишацька", "True"), 9))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ambiguous_in_oblast is \"True\"");
        }

        @Test
        @DisplayName("an empty ambiguity flag aborts — an absent value is not a false one")
        void should_abort_when_theAmbiguityFlagIsEmpty() {
            assertThatThrownBy(() -> migration.toRow(List.of("UA1", "Шишацька", ""), 10))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("expected exactly \"true\" or \"false\"");
        }

        @Test
        @DisplayName("both accepted spellings still parse — the guard refuses noise, not data")
        void should_parseBothFlags_when_theTokenIsExactlyTrueOrFalse() {
            // The live negative control. Without it a reader that rejected EVERYTHING would pass
            // all three assertions above while making the migration unrunnable.
            assertThat(migration.toRow(List.of("UA1", "Шишацька", "true"), 11).ambiguous()).isTrue();
            assertThat(migration.toRow(List.of("UA2", "Шишацька", "false"), 12).ambiguous()).isFalse();
        }

        @Test
        @DisplayName("a doubled quote is an escaped quote, not a field terminator")
        void should_readAnEscapedQuote_when_theFieldContainsADoubledQuote() {
            List<String> fields = migration.splitQuoted("\"UA1\",\"Кам\"\"янська\",\"true\"", 2);

            assertThat(fields).containsExactly("UA1", "Кам\"янська", "true");
        }
    }

    // -----------------------------------------------------------------------
    // The row contract the CHECK constraint also enforces — toRow
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("an ambiguous row with no hromada")
    class AmbiguousWithoutHromada {

        @Test
        @DisplayName("aborts naming the settlement, before PostgreSQL has to say it less usefully")
        void should_abort_when_anAmbiguousRowCarriesNoHromada() {
            assertThatThrownBy(() ->
                    migration.toRow(List.of("UA63060090010036392", "", "true"), 99))
                    .as("chk_cities_hromada_disambiguates would also reject this, but its message "
                            + "names a table and a constraint; the operator needs the KATOTTH code "
                            + "and the file that produced it")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("UA63060090010036392")
                    .hasMessageContaining("settlement_hromadas.csv")
                    .hasMessageContaining("chk_cities_hromada_disambiguates");
        }

        @Test
        @DisplayName("an empty hromada is fine while the row is unambiguous — 76 % of rows are")
        void should_mapEmptyHromadaToNull_when_theRowIsUnambiguous() {
            var row = migration.toRow(List.of("UA32000000010085013", "", "false"), 5);

            assertThat(row.hromadaNameUk())
                    .as("the CSV writes an empty field for «no hromada»; storing \"\" instead of "
                            + "NULL would satisfy the CHECK with a value that renders as an empty "
                            + "label part on the client")
                    .isNull();
            assertThat(row.ambiguous()).isFalse();
        }

        @Test
        @DisplayName("a populated ambiguous row parses — the guard is not always-on")
        void should_parse_when_anAmbiguousRowCarriesItsHromada() {
            var row = migration.toRow(List.of("UA63100050010032187", "Лозівська", "true"), 6);

            assertThat(row.katotthCode()).isEqualTo("UA63100050010032187");
            assertThat(row.hromadaNameUk()).isEqualTo("Лозівська");
            assertThat(row.ambiguous()).isTrue();
        }
    }

    // -----------------------------------------------------------------------
    // The write, and the row-count guard that is the occupied-territory guard — applyAll
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("the set-based write and the row count it refuses to be short on")
    class SetBasedWrite {

        private final V175__backfill_settlement_hromadas.HromadaRow row =
                new V175__backfill_settlement_hromadas.HromadaRow(
                        "UA14020010010059145", "Тестівська", true);

        private final V175__backfill_settlement_hromadas.HromadaRow sibling =
                new V175__backfill_settlement_hromadas.HromadaRow(
                        "UA63100050010032187", "Лозівська", true);

        private Connection connection;
        private PreparedStatement update;
        private PreparedStatement diagnose;

        @BeforeEach
        void wireConnection() throws Exception {
            connection = mock(Connection.class);
            update = mock(PreparedStatement.class);
            diagnose = mock(PreparedStatement.class);
            when(connection.prepareStatement(contains("UPDATE cities"))).thenReturn(update);
            when(connection.prepareStatement(contains("LEFT JOIN cities"))).thenReturn(diagnose);
            when(connection.createArrayOf(anyString(), any())).thenReturn(mock(Array.class));
        }

        @Test
        @DisplayName("aborts naming the code when a settlement matches no `cities` row")
        void should_abort_when_aCodeUpdatesNoRow() throws Exception {
            // THE OCCUPIED-TERRITORY GUARD. It is what makes V175 structurally incapable of
            // INSERTing a settlement: it can only UPDATE rows V171 imported, and V171's list is
            // filtered against docs/qa/occupied-settlements.md. A code that matches nothing is
            // either a settlement the exclusion set removed on purpose or a genuine drift between
            // the two generated files, and both must stop the release.
            ResultSet unmatched = mock(ResultSet.class);
            when(unmatched.next()).thenReturn(true, false);
            when(unmatched.getString(1)).thenReturn("UA14020010010059145");
            when(update.executeUpdate()).thenReturn(0);
            when(diagnose.executeQuery()).thenReturn(unmatched);

            assertThatThrownBy(() -> migration.applyAll(connection, List.of(row)))
                    .as("silently skipping it would leave that settlement with no hromada and no "
                            + "flag — indistinguishable at read time from one that is genuinely "
                            + "unambiguous, which is the bug this phase exists to remove")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("wrote 0 `cities` rows for 1 rows")
                    .hasMessageContaining("UA14020010010059145")
                    .hasMessageContaining("match no `cities` row");
        }

        @Test
        @DisplayName("aborts on a REPEATED code too — the set-based form the batch could not catch")
        void should_abort_when_theFileRepeatsAKatotthCode() throws Exception {
            // The strengthening the set-based rewrite brought, asserted rather than claimed in a
            // comment. `UPDATE … FROM unnest(...)` touches a duplicated settlement's row ONCE, so
            // two CSV rows land one write and the count falls short. The batched shape this
            // replaced saw two update counts of 1 and passed — a hand-edited file that duplicated
            // one settlement and dropped another loaded with no error anywhere.
            ResultSet none = mock(ResultSet.class);
            when(none.next()).thenReturn(false);
            when(update.executeUpdate()).thenReturn(1);
            when(diagnose.executeQuery()).thenReturn(none);

            assertThatThrownBy(() -> migration.applyAll(connection, List.of(row, sibling)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("wrote 1 `cities` rows for 2 rows")
                    .hasMessageContaining("repeats a katotth_code");
        }

        @Test
        @DisplayName("a full write is counted instead — the guard is not always-on")
        void should_countTheRows_when_everyCodeMatches() throws Exception {
            when(update.executeUpdate()).thenReturn(2);

            int written = migration.applyAll(connection, List.of(row, sibling));

            assertThat(written).isEqualTo(2);
            verify(connection, never()).prepareStatement(contains("LEFT JOIN cities"));
        }

        @Test
        @DisplayName("a null hromada is a NULL array element, never the string \"null\"")
        void should_bindSqlNull_when_theRowHasNoHromada() throws Exception {
            when(update.executeUpdate()).thenReturn(1);
            var hromadaLess = new V175__backfill_settlement_hromadas.HromadaRow(
                    "UA32000000010085013", null, false);
            ArgumentCaptor<Object[]> elements = ArgumentCaptor.forClass(Object[].class);

            migration.applyAll(connection, List.of(hromadaLess));

            verify(connection, times(3)).createArrayOf(anyString(), elements.capture());
            assertThat(elements.getAllValues().get(1))
                    .as("the hromada array must carry a null ELEMENT; PgJDBC renders that as SQL "
                            + "NULL, while the string \"null\" would satisfy "
                            + "chk_cities_hromada_disambiguates with a value that renders as an "
                            + "empty label part on the client")
                    .containsExactly((Object) null);
        }

        @Test
        @DisplayName("25 697 rows are ONE statement — the 40 s the test suite used to spend")
        void should_issueASingleStatement_when_theWholeCsvIsApplied() throws Exception {
            // The falsification for the performance half of Phase 327's fix. The per-row shape
            // this replaced cost 2 325 ms of V175's 2 496 ms, replayed by all 16 classes that
            // start a PostgreSQL container — ~40 s of every full suite run. A revert to
            // addBatch/executeBatch is invisible to every other test in this file and in the
            // integration suite: the DATA it writes is identical. This is the only assertion that
            // sees it.
            List<V175__backfill_settlement_hromadas.HromadaRow> many =
                    java.util.stream.IntStream.range(0, 2_500)
                            .mapToObj(i -> new V175__backfill_settlement_hromadas.HromadaRow(
                                    "UA%017d".formatted(i), "Тестівська", false))
                            .toList();
            when(update.executeUpdate()).thenReturn(many.size());

            migration.applyAll(connection, many);

            verify(connection, times(1)).prepareStatement(contains("UPDATE cities"));
            verify(update, times(1)).executeUpdate();
            verify(update, never()).addBatch();
            verify(update, never()).executeBatch();
        }

        @Test
        @DisplayName("closes the statement even when the write throws")
        void should_closeTheStatement_when_theUpdateFails() throws Exception {
            when(update.executeUpdate()).thenThrow(new SQLException("deadlock detected"));

            assertThatThrownBy(() -> migration.applyAll(connection, List.of(row)))
                    .isInstanceOf(SQLException.class);

            verify(update).close();
        }
    }

    // -----------------------------------------------------------------------
    // The index rebuild the settlement endpoint's safety rests on
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("the trigram index rebuild that pays for the full-table UPDATE")
    class TrigramIndexRebuild {

        @Test
        @DisplayName("rebuilds idx_cities_name_uk_trgm and only THEN re-analyzes cities")
        void should_reindexBeforeAnalyzing_when_theBackfillHasWritten() throws Exception {
            // The ORDER is the whole content of this test, and it is the one property no
            // post-migration measurement can see. V175's javadoc records the measurement: ANALYZE
            // over the still-bloated index makes the planner abandon it for MORE terms, not fewer
            // (benign Seq Scans 1 -> 3, cost budget 81 632 -> 3 870). So an edit that keeps both
            // statements but swaps them leaves a database that is worse than one that ran neither,
            // with every count, every row and every byte of the backfill identical.
            //
            // This does NOT prove migrate() still CALLS this method — deleting the call leaves this
            // test green. SettlementSearchCostGuardIT
            // .should_keepTheTrigramIndexCompact_when_theMigrationsHaveApplied is what covers that,
            // by measuring pg_relation_size after the real chain has run. Neither subsumes the
            // other.
            Connection connection = mock(Connection.class);
            Statement statement = mock(Statement.class);
            when(connection.createStatement()).thenReturn(statement);

            migration.restoreTrigramIndexHealth(connection);

            InOrder order = inOrder(statement);
            order.verify(statement).execute("REINDEX INDEX idx_cities_name_uk_trgm");
            order.verify(statement).execute("ANALYZE cities");
        }

        @Test
        @DisplayName("rebuilds it plainly, never CONCURRENTLY — the migration is transactional")
        void should_notReindexConcurrently_when_theRebuildIsIssued() throws Exception {
            // REINDEX CONCURRENTLY cannot run inside a transaction block, and V175 is deliberately
            // transactional so a mid-batch abort rolls the whole backfill back. Someone reaching
            // for CONCURRENTLY to avoid the table lock would not fail here at compile time or in
            // any unit test — it would fail at deploy, on Railway, mid-release.
            Connection connection = mock(Connection.class);
            Statement statement = mock(Statement.class);
            when(connection.createStatement()).thenReturn(statement);
            ArgumentCaptor<String> issued = ArgumentCaptor.forClass(String.class);

            migration.restoreTrigramIndexHealth(connection);

            verify(statement, times(2)).execute(issued.capture());
            assertThat(issued.getAllValues())
                    .as("CONCURRENTLY is illegal inside the transaction Flyway opens for this "
                            + "migration; the fail-closed backfill is worth the millisecond lock "
                            + "on a 25 698-row table")
                    .noneMatch(sql -> sql.toUpperCase().contains("CONCURRENTLY"));
        }

        @Test
        @DisplayName("closes the statement even when the rebuild throws")
        void should_closeTheStatement_when_theReindexFails() throws Exception {
            // try-with-resources, asserted because a leaked Statement on Flyway's own connection
            // outlives the migration and the failure it leaks on is the one that matters.
            Connection connection = mock(Connection.class);
            Statement statement = mock(Statement.class);
            when(connection.createStatement()).thenReturn(statement);
            when(statement.execute(anyString()))
                    .thenThrow(new SQLException("could not obtain lock"));

            assertThatThrownBy(() -> migration.restoreTrigramIndexHealth(connection))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("could not obtain lock");

            verify(statement).close();
        }
    }

    // -----------------------------------------------------------------------
    // Checksum from the CSV bytes
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("getChecksum is a CRC32 of the hromada CSV bytes")
    class ChecksumDerivation {

        @Test
        @DisplayName("equals the CRC32 of the committed settlement_hromadas.csv, recomputed here")
        void should_equalTheCrc32OfTheCommittedCsv_when_computedIndependently() throws Exception {
            byte[] csv;
            try (InputStream in = getClass().getClassLoader()
                    .getResourceAsStream(V175__backfill_settlement_hromadas.CSV_RESOURCE)) {
                assertThat(in)
                        .as("%s must be on the classpath — V175 cannot run without it",
                                V175__backfill_settlement_hromadas.CSV_RESOURCE)
                        .isNotNull();
                csv = in.readAllBytes();
            }
            CRC32 expected = new CRC32();
            expected.update(csv);

            assertThat(migration.getChecksum()).isEqualTo((int) expected.getValue());
        }

        @Test
        @DisplayName("tracks its OWN file, not V171's — that separation is the whole phase")
        void should_differFromV171sChecksum_when_bothAreComputed() {
            assertThat(migration.getChecksum())
                    .as("settlements.csv is FROZEN by V171's checksum. Phase 327 ships the hromada "
                            + "as a separate resource precisely so that file stays byte-identical; "
                            + "if the two migrations ever derived from the same bytes, refreshing "
                            + "either list would crash-loop Flyway on an applied database.")
                    .isNotEqualTo(new V171__import_free_settlements().getChecksum());
        }

        /**
         * Drives the derivation with DIFFERENT bytes rather than asserting an arithmetic property
         * of CRC32 (which would hold whatever the migration did). A subclass returning one edited
         * byte must produce a different Flyway checksum — that, and only that, is what makes
         * "refresh the hromada CSV" a detectable mutation of an applied migration.
         */
        @Test
        @DisplayName("a single changed CSV byte changes the checksum — the immutability claim")
        void should_produceADifferentChecksum_when_theResourceBytesChange() {
            byte[] committed = migration.readResourceBytes();
            byte[] edited = committed.clone();
            edited[edited.length - 2] ^= 0x01;

            assertThat(new V999__edited_hromada_csv(edited).getChecksum())
                    .isNotEqualTo(migration.getChecksum());
        }
    }

    /**
     * Named, not anonymous: {@code BaseJavaMigration}'s constructor parses the SIMPLE CLASS NAME to
     * derive the version and throws on anything not starting with {@code V} or {@code R}. The
     * version is never used — this subclass exists only to feed
     * {@link V175__backfill_settlement_hromadas#getChecksum()} different bytes.
     */
    static final class V999__edited_hromada_csv extends V175__backfill_settlement_hromadas {

        private final byte[] bytes;

        V999__edited_hromada_csv(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        byte[] readResourceBytes() {
            return bytes;
        }
    }
}
