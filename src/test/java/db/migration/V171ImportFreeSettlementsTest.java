package db.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Failure-branch coverage for {@link V171__import_free_settlements} — the four guards that make
 * the settlement import safe, none of which a green migration run ever reaches.
 *
 * <p><b>Why this class exists.</b> {@code LocalityTaxonomySeedMigrationTest} proves what a
 * SUCCESSFUL V171 leaves in {@code cities}. It cannot prove anything about what V171 does with a
 * bad input, because a bad input aborts the migration and therefore the Spring context — every
 * test in that class errors and none of them reports the guard. So the guards were untested:
 * a malformed CSV reader, an unknown oblast, a missing Kyiv row, and the CRC32-over-the-CSV
 * checksum that makes the imported data immutable once applied.
 *
 * <p>Plain JUnit + Mockito, no Spring and no database: three of the four branches are reached
 * before any SQL executes, and the fourth is a pure function of the classpath resource. A
 * Testcontainers run for this would be minutes of Postgres to assert an {@code if}.
 *
 * <p>Package {@code db.migration} mirrors the class under test so the package-private seams
 * documented in its Javadoc are reachable without reflection.
 */
@DisplayName("V171__import_free_settlements — the guards a successful run never reaches")
class V171ImportFreeSettlementsTest {

    private final V171__import_free_settlements migration = new V171__import_free_settlements();

    // -----------------------------------------------------------------------
    // Malformed CSV (V171 :172 — splitQuoted / toRow)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("malformed CSV is rejected, never guessed at")
    class MalformedCsv {

        @Test
        @DisplayName("an unquoted field aborts — the reader is QUOTE_ALL-strict by design")
        void should_abort_when_aFieldIsNotQuoted() {
            assertThatThrownBy(() -> migration.splitQuoted("\"UA1\",UA2", 7))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("V171 blocked: malformed")
                    .hasMessageContaining("line 7")
                    .hasMessageContaining("field 2 is not quoted");
        }

        @Test
        @DisplayName("an unterminated quoted field aborts rather than silently truncating the row")
        void should_abort_when_aQuotedFieldIsUnterminated() {
            assertThatThrownBy(() -> migration.splitQuoted("\"UA1\",\"Херсон", 12))
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
        @DisplayName("the wrong column count aborts — a re-shaped CSV must not import partially")
        void should_abort_when_theRowHasTheWrongColumnCount() {
            assertThatThrownBy(() -> migration.toRow(List.of("UA1", "UA2", "Місто"), 42))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("expected 6 columns, got 3");
        }

        @Test
        @DisplayName("a doubled quote is an escaped quote, not a field terminator")
        void should_readAnEscapedQuote_when_theFieldContainsADoubledQuote() {
            // QUOTE_ALL's only escape: a literal " inside a field is written "".
            List<String> fields = migration.splitQuoted("\"UA1\",\"Кам\"\"янка\"", 2);

            assertThat(fields).containsExactly("UA1", "Кам\"янка");
        }
    }

    // -----------------------------------------------------------------------
    // Unknown oblast (V171 :245)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("a settlement naming an oblast that is not in `oblasts`")
    class UnknownOblast {

        @Test
        @DisplayName("aborts before any INSERT is batched, naming the settlement and the oblast")
        void should_abortWithoutWriting_when_theOblastCodeDoesNotResolve() throws Exception {
            Connection connection = mock(Connection.class);
            PreparedStatement statement = mock(PreparedStatement.class);
            when(connection.prepareStatement(anyString())).thenReturn(statement);
            var orphan = new V171__import_free_settlements.SettlementRow(
                    "UA14020010010059145", "UA14000000000091971",
                    "Тестове", "Testove", "VILLAGE", false);

            assertThatThrownBy(() -> migration.upsertAll(connection, List.of(orphan), Map.of()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("V171 blocked: settlement UA14020010010059145")
                    .hasMessageContaining("UA14000000000091971")
                    .hasMessageContaining("not in `oblasts`");

            verify(statement, never()).addBatch();
            verify(statement, never()).executeBatch();
        }

        @Test
        @DisplayName("a resolvable oblast batches the row instead — the guard is not always-on")
        void should_batchTheRow_when_theOblastCodeResolves() throws Exception {
            Connection connection = mock(Connection.class);
            PreparedStatement statement = mock(PreparedStatement.class);
            when(connection.prepareStatement(anyString())).thenReturn(statement);
            when(statement.executeBatch()).thenReturn(new int[]{1});
            var row = new V171__import_free_settlements.SettlementRow(
                    "UA14020010010059145", "UA14000000000091971",
                    "Тестове", "Testove", "VILLAGE", false);

            int written = migration.upsertAll(
                    connection, List.of(row), Map.of("UA14000000000091971", UUID.randomUUID()));

            assertThat(written).isEqualTo(1);
            verify(statement).addBatch();
        }
    }

    // -----------------------------------------------------------------------
    // Missing Kyiv row (V171 :288)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("the Kyiv is_major flag-raise")
    class KyivFlag {

        @Test
        @DisplayName("aborts when no Kyiv row is updated — Kyiv is category K and never in the CSV")
        void should_abort_when_theKyivRowIsAbsent() throws Exception {
            Connection connection = mock(Connection.class);
            PreparedStatement statement = mock(PreparedStatement.class);
            when(connection.prepareStatement(anyString())).thenReturn(statement);
            when(statement.executeUpdate()).thenReturn(0);

            assertThatThrownBy(() -> migration.markKyivMajor(connection))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("expected exactly 1 Kyiv row")
                    .hasMessageContaining("UA80000000000093317")
                    .hasMessageContaining("updated 0");
        }

        @Test
        @DisplayName("aborts when MORE than one row is updated — a duplicate Kyiv is also a failure")
        void should_abort_when_moreThanOneKyivRowIsUpdated() throws Exception {
            Connection connection = mock(Connection.class);
            PreparedStatement statement = mock(PreparedStatement.class);
            when(connection.prepareStatement(anyString())).thenReturn(statement);
            when(statement.executeUpdate()).thenReturn(2);

            assertThatThrownBy(() -> migration.markKyivMajor(connection))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("updated 2");
        }

        @Test
        @DisplayName("passes on exactly one updated row — the guard is not always-on")
        void should_succeed_when_exactlyOneKyivRowIsUpdated() throws Exception {
            Connection connection = mock(Connection.class);
            PreparedStatement statement = mock(PreparedStatement.class);
            when(connection.prepareStatement(anyString())).thenReturn(statement);
            when(statement.executeUpdate()).thenReturn(1);

            migration.markKyivMajor(connection);

            verify(statement).setString(1, "UA80000000000093317");
        }
    }

    // -----------------------------------------------------------------------
    // Checksum from the CSV bytes (V171 :99)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("getChecksum is a CRC32 of the CSV bytes")
    class ChecksumDerivation {

        @Test
        @DisplayName("equals the CRC32 of the committed settlements.csv, recomputed independently")
        void should_equalTheCrc32OfTheCommittedCsv_when_computedIndependently() throws Exception {
            byte[] csv;
            try (InputStream in = getClass().getClassLoader()
                    .getResourceAsStream(V171__import_free_settlements.CSV_RESOURCE)) {
                assertThat(in)
                        .as("%s must be on the classpath — V171 cannot run without it",
                                V171__import_free_settlements.CSV_RESOURCE)
                        .isNotNull();
                csv = in.readAllBytes();
            }
            CRC32 expected = new CRC32();
            expected.update(csv);

            assertThat(migration.getChecksum())
                    .as("the Flyway checksum IS the CSV's CRC32 — this is what makes the imported "
                            + "data immutable once V171 has been applied anywhere")
                    .isEqualTo((int) expected.getValue());
        }

        /**
         * Drives the derivation with DIFFERENT bytes rather than asserting an arithmetic property
         * of CRC32 (which would hold whatever the migration did). A subclass that returns one
         * edited byte must produce a different Flyway checksum — that, and only that, is what
         * makes "refresh the settlement CSV" a detectable mutation of an applied migration.
         */
        @Test
        @DisplayName("a single changed CSV byte changes the checksum — the whole immutability claim")
        void should_produceADifferentChecksum_when_theResourceBytesChange() {
            byte[] committed = migration.readResourceBytes();
            byte[] edited = committed.clone();
            // The realistic maintenance edit: one settlement's row is corrected in place.
            edited[edited.length - 2] ^= 0x01;
            assertThat(new V999__edited_csv(edited).getChecksum())
                    .as("editing settlements.csv after V171 is applied must move the Flyway "
                            + "checksum — that is exactly why the migration-immutability workflow "
                            + "now guards src/main/resources/db/data/*.csv, not just "
                            + "db/migration/*.sql, and it is the outage this class documents")
                    .isNotEqualTo(migration.getChecksum());
        }
    }

    /**
     * Named, not anonymous: {@code BaseJavaMigration}'s constructor parses the SIMPLE CLASS NAME
     * to derive the version, and throws {@code FlywayException} on anything that does not start
     * with {@code V} or {@code R}. The version is never used — this subclass exists only to feed
     * {@link V171__import_free_settlements#getChecksum()} different bytes.
     */
    static final class V999__edited_csv extends V171__import_free_settlements {

        private final byte[] bytes;

        V999__edited_csv(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        byte[] readResourceBytes() {
            return bytes;
        }
    }
}
