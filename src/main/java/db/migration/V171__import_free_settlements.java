package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.CRC32;

/**
 * V171 — import the free settlements of Ukraine into {@code cities} (Phase 325, data half).
 *
 * <p>Companion to {@code V170__widen_cities_to_full_settlement_taxonomy.sql}, which does the
 * schema and the corrective deletes. This migration loads the 25 697-row CSV resource at
 * {@value #CSV_RESOURCE}, taking {@code cities} from V53's 356 category-M cities to every
 * settlement the product can serve — villages included.
 *
 * <p><b>Why Java and a CSV, not SQL</b> (phase-325 D4): ~25 700 {@code INSERT} statements are
 * unreviewable in a diff and slow to replay. The CSV is a reviewable table; this class is the
 * loader.
 *
 * <p><b>Why there is no {@code occupation_status} column</b> (phase-325 D3): the CSV is a
 * PRE-FILTERED free-settlement list — the full KATOTTH classifier minus Crimea/Sevastopol
 * wholesale, minus the 2 958 currently-occupied settlements of Phase 324. No occupied row is
 * ever offered to the database, so the occupied-territory ban holds structurally and not
 * through a predicate a future query could omit. Regenerate the CSV with
 * {@code python3 scripts/locality/build_settlement_import.py} ({@code --check} is the drift gate).
 *
 * <p><b>Why upsert rather than truncate-and-load:</b> V53 already seeded 352 of these
 * settlements, and {@code users.city_id} / {@code salons.city_id} / {@code city_districts.city_id}
 * FK those rows by their surrogate UUID. Deleting and re-inserting would either break the FKs or
 * silently repoint live addresses at new ids. {@code ON CONFLICT (katotth_code) DO UPDATE} keeps
 * every existing id and refreshes the payload, so the load is also idempotent.
 *
 * <p><b>Visibility:</b> the CSV reader ({@link #parseCsv()}, {@link #splitQuoted}, {@link #toRow})
 * and the two JDBC steps that abort on a bad input ({@link #upsertAll}, {@link #markKyivMajor}),
 * plus {@link #readResourceBytes()} (so a subclass can prove {@link #getChecksum()} really tracks
 * those bytes), are package-private, not private, so {@code db.migration.V171ImportFreeSettlementsTest} can
 * drive their failure branches directly. Those branches are the whole safety story of this
 * migration and they are unreachable from a green run: a test that only replays the happy path
 * proves the CSV is well-formed today, never that a malformed one would be rejected. Nothing
 * outside the test calls them.
 *
 * <p><b>Checksum:</b> {@link #getChecksum()} is derived from the CSV bytes, so editing the CSV
 * after this migration has run anywhere fails Flyway validation loudly rather than letting the
 * table drift from its source. That is deliberate — applied migrations are immutable; a
 * corrected settlement list ships as the next free version.
 */
public class V171__import_free_settlements extends BaseJavaMigration {

    private static final Logger log = LoggerFactory.getLogger(V171__import_free_settlements.class);

    /** Classpath location of the generated free-settlement list. */
    static final String CSV_RESOURCE = "db/data/settlements.csv";

    /**
     * Expected data-row count. Pinned so a truncated, re-filtered or hand-edited CSV aborts the
     * migration instead of silently importing a partial taxonomy.
     */
    private static final int EXPECTED_ROWS = 25_697;

    /**
     * Expected {@code is_major} count across the whole table: 49 in the CSV plus Kyiv, which is
     * KATOTTH category K (an oblast-equivalent, not a level-4 settlement) and therefore is not a
     * CSV row — V53 already inserted it and this migration only raises its flag.
     */
    private static final int EXPECTED_MAJOR = 50;

    private static final String KYIV_KATOTTH_CODE = "UA80000000000093317";

    private static final int BATCH_SIZE = 1_000;

    private static final String UPSERT_SQL = """
            INSERT INTO cities (oblast_id, katotth_code, name_uk, name_en, settlement_type, is_major)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (katotth_code) DO UPDATE SET
                oblast_id       = EXCLUDED.oblast_id,
                name_uk         = EXCLUDED.name_uk,
                name_en         = EXCLUDED.name_en,
                settlement_type = EXCLUDED.settlement_type,
                is_major        = EXCLUDED.is_major
            """;

    /** One CSV data row. */
    record SettlementRow(
            String katotthCode,
            String oblastKatotthCode,
            String nameUk,
            String nameEn,
            String settlementType,
            boolean major
    ) {}

    @Override
    public Integer getChecksum() {
        CRC32 crc = new CRC32();
        crc.update(readResourceBytes());
        return (int) crc.getValue();
    }

    @Override
    public void migrate(Context context) throws Exception {
        List<SettlementRow> rows = parseCsv();
        if (rows.size() != EXPECTED_ROWS) {
            throw new IllegalStateException(
                    "V171 blocked: " + CSV_RESOURCE + " holds " + rows.size()
                            + " data rows, expected " + EXPECTED_ROWS
                            + ". Regenerate it with scripts/locality/build_settlement_import.py "
                            + "and ship the correction as the next migration version.");
        }

        Connection connection = context.getConnection(); // owned by Flyway — never closed here
        Map<String, UUID> oblastIds = loadOblastIds(connection);
        int written = upsertAll(connection, rows, oblastIds);
        markKyivMajor(connection);
        verifyFinalState(connection);

        log.info("V171: imported {} free settlements ({} rows written)", rows.size(), written);
    }

    // ------------------------------------------------------------------ CSV

    byte[] readResourceBytes() {
        try (InputStream in = resourceStream()) {
            return in.readAllBytes();
        } catch (Exception e) {
            throw new IllegalStateException("V171: cannot read " + CSV_RESOURCE, e);
        }
    }

    private InputStream resourceStream() {
        InputStream in = getClass().getClassLoader().getResourceAsStream(CSV_RESOURCE);
        if (in == null) {
            throw new IllegalStateException(
                    "V171 blocked: " + CSV_RESOURCE + " is not on the classpath. It is generated "
                            + "by scripts/locality/build_settlement_import.py.");
        }
        return in;
    }

    List<SettlementRow> parseCsv() throws Exception {
        List<SettlementRow> rows = new ArrayList<>(EXPECTED_ROWS);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resourceStream(), StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (header == null || !header.startsWith("\"katotth_code\"")) {
                throw new IllegalStateException(
                        "V171 blocked: unexpected header in " + CSV_RESOURCE + ": " + header);
            }
            String line;
            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isEmpty()) {
                    continue;
                }
                rows.add(toRow(splitQuoted(line, lineNumber), lineNumber));
            }
        }
        return rows;
    }

    /**
     * Splits one fully-quoted CSV line. The generator writes {@code QUOTE_ALL}, so every field is
     * delimited and the only escape is a doubled quote — a deliberately strict reader that rejects
     * anything else rather than guessing at a dialect.
     */
    List<String> splitQuoted(String line, int lineNumber) {
        List<String> fields = new ArrayList<>(6);
        StringBuilder field = new StringBuilder();
        int i = 0;
        while (i < line.length()) {
            if (line.charAt(i) != '"') {
                throw malformed(lineNumber, "field " + (fields.size() + 1) + " is not quoted");
            }
            i++;
            while (true) {
                if (i >= line.length()) {
                    throw malformed(lineNumber, "unterminated quoted field");
                }
                char c = line.charAt(i);
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        field.append('"');
                        i += 2;
                        continue;
                    }
                    i++;
                    break;
                }
                field.append(c);
                i++;
            }
            fields.add(field.toString());
            field.setLength(0);
            if (i < line.length()) {
                if (line.charAt(i) != ',') {
                    throw malformed(lineNumber, "expected ',' after a quoted field");
                }
                i++;
            }
        }
        return fields;
    }

    private IllegalStateException malformed(int lineNumber, String detail) {
        return new IllegalStateException(
                "V171 blocked: malformed " + CSV_RESOURCE + " at line " + lineNumber + " — " + detail);
    }

    SettlementRow toRow(List<String> fields, int lineNumber) {
        if (fields.size() != 6) {
            throw malformed(lineNumber, "expected 6 columns, got " + fields.size());
        }
        return new SettlementRow(
                fields.get(0), fields.get(1), fields.get(2), fields.get(3), fields.get(4),
                Boolean.parseBoolean(fields.get(5)));
    }

    // ----------------------------------------------------------------- JDBC

    private Map<String, UUID> loadOblastIds(Connection connection) throws Exception {
        Map<String, UUID> ids = new HashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT katotth_code, id FROM oblasts")) {
            while (rs.next()) {
                ids.put(rs.getString(1), (UUID) rs.getObject(2));
            }
        }
        return ids;
    }

    int upsertAll(Connection connection, List<SettlementRow> rows, Map<String, UUID> oblastIds)
            throws Exception {
        int written = 0;
        try (PreparedStatement statement = connection.prepareStatement(UPSERT_SQL)) {
            int pending = 0;
            for (SettlementRow row : rows) {
                UUID oblastId = oblastIds.get(row.oblastKatotthCode());
                if (oblastId == null) {
                    throw new IllegalStateException(
                            "V171 blocked: settlement " + row.katotthCode() + " (" + row.nameUk()
                                    + ") names oblast " + row.oblastKatotthCode()
                                    + ", which is not in `oblasts`. V170 adds Донецька and "
                                    + "Луганська; Crimea and Sevastopol are excluded by design.");
                }
                statement.setObject(1, oblastId);
                statement.setString(2, row.katotthCode());
                statement.setString(3, row.nameUk());
                statement.setString(4, row.nameEn());
                statement.setString(5, row.settlementType());
                statement.setBoolean(6, row.major());
                statement.addBatch();
                if (++pending == BATCH_SIZE) {
                    written += countUpdates(statement.executeBatch());
                    pending = 0;
                }
            }
            if (pending > 0) {
                written += countUpdates(statement.executeBatch());
            }
        }
        return written;
    }

    private int countUpdates(int[] results) {
        int total = 0;
        for (int result : results) {
            total += Math.max(result, 0);
        }
        return total;
    }

    /**
     * Kyiv is KATOTTH category K — an oblast-equivalent that is simultaneously its own city — so
     * it is not a level-4 settlement and never appears in the CSV. V53 inserted its {@code cities}
     * row; this raises the flag that makes it the first entry of the "biggest places" list.
     */
    void markKyivMajor(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE cities SET is_major = TRUE WHERE katotth_code = ?")) {
            statement.setString(1, KYIV_KATOTTH_CODE);
            int updated = statement.executeUpdate();
            if (updated != 1) {
                throw new IllegalStateException(
                        "V171 blocked: expected exactly 1 Kyiv row (" + KYIV_KATOTTH_CODE
                                + ") to flag as major, updated " + updated);
            }
        }
    }

    /**
     * Post-conditions asserted against the real table, not against the parsed file — a count that
     * only ever reads the CSV proves nothing about what landed.
     */
    private void verifyFinalState(Connection connection) throws Exception {
        long total = scalar(connection, "SELECT COUNT(*) FROM cities");
        long expectedTotal = EXPECTED_ROWS + 1L; // + Kyiv (category K, seeded by V53)
        if (total != expectedTotal) {
            throw new IllegalStateException(
                    "V171 blocked: cities holds " + total + " rows after import, expected "
                            + expectedTotal + " (" + EXPECTED_ROWS + " settlements + Kyiv).");
        }

        long major = scalar(connection, "SELECT COUNT(*) FROM cities WHERE is_major");
        if (major != EXPECTED_MAJOR) {
            throw new IllegalStateException(
                    "V171 blocked: " + major + " rows are flagged is_major, expected " + EXPECTED_MAJOR + ".");
        }

        long crimean = scalar(connection,
                "SELECT COUNT(*) FROM cities WHERE katotth_code LIKE 'UA01%' OR katotth_code LIKE 'UA85%'");
        if (crimean != 0) {
            throw new IllegalStateException(
                    "V171 blocked: " + crimean + " row(s) resolve to Crimea (UA01) or Sevastopol "
                            + "(UA85), which are excluded wholesale.");
        }
    }

    private long scalar(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
