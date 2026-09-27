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
import java.util.List;
import java.util.zip.CRC32;

/**
 * V175 — load the hromada parent and the ambiguity flag onto {@code cities} (Phase 327, data half).
 *
 * <p>Companion to {@code V174__add_cities_hromada_disambiguation.sql}, which adds the two columns
 * and the conditional CHECK. This migration fills them from the 25 697-row CSV resource at
 * {@value #CSV_RESOURCE}.
 *
 * <p><b>Why a SECOND resource instead of a widened {@code settlements.csv}.</b> V171 is applied and
 * derives its Flyway checksum from {@code db/data/settlements.csv}'s BYTES, and its own javadoc
 * states the rule: <i>"a corrected settlement list ships as the next free version"</i>. Editing that
 * file would mutate an applied migration's checksum — the defect class that crash-looped Railway on
 * V49 — and the migration-immutability workflow now guards {@code db/data/*.csv} for exactly this
 * reason. So the hromada ships BESIDE the settlement list, in a file of its own, loaded here, and
 * {@link #getChecksum()} tracks THIS file only. {@code settlements.csv} stays byte-identical and
 * V171 keeps validating on every database that has already applied it.
 *
 * <p><b>Why UPDATE and not a re-import.</b> The rows already exist: V171 upserted all 25 697 of them
 * and {@code users.city_id} / {@code salons.city_id} / {@code city_districts.city_id} FK them by
 * surrogate UUID. This migration adds two facts to rows it must not otherwise touch, so it joins on
 * the stable {@code katotth_code} and writes nothing else.
 *
 * <p><b>Why the whole set and not only the ambiguous subset.</b> The CSV carries a hromada for every
 * settlement, so the column is a complete parent map rather than a sparse patch — «this settlement
 * has no hromada» is then distinguishable from «this settlement was not in the ambiguous subset»,
 * and the flag stays a plain fact about the row. The projection in {@code CityRepository} is what
 * decides visibility ({@code CASE WHEN c.ambiguous_in_oblast THEN c.hromada_name_uk END}), not the
 * presence or absence of stored data.
 *
 * <p><b>The CHECK is already live when this runs.</b> V174 adds
 * {@code chk_cities_hromada_disambiguates} BEFORE this migration writes a row, so a CSV claiming a
 * row is ambiguous without saying what disambiguates it is rejected by PostgreSQL rather than
 * landing — and because the backfill is a single statement, that rejection takes the whole write
 * with it. The generator refuses to emit such a row as well; two independent refusals, because
 * this one cannot be bypassed by hand-editing the file.
 *
 * <p><b>Idempotent.</b> Re-running writes the same two values onto the same rows.
 *
 * <p><b>Visibility:</b> the CSV reader ({@link #parseCsv()}, {@link #splitQuoted},
 * {@link #toRow}), the JDBC step that aborts on a short write ({@link #applyAll}),
 * {@link #readResourceBytes()} and the index rebuild
 * ({@link #restoreTrigramIndexHealth(java.sql.Connection)}) are package-private, not private, so
 * {@code db.migration.V175BackfillSettlementHromadasTest} can drive their failure branches
 * directly. Those branches are the safety story of this migration and are unreachable from a green
 * run: a test that only replays the happy path proves the CSV is well-formed today, never that a
 * malformed one would be rejected. Nothing outside the test calls them.
 *
 * <p><b>On the duplicated CSV reader.</b> {@link V171__import_free_settlements} carries an
 * identical strict QUOTE_ALL reader. It is not shared, and could not be: V171 is an applied
 * migration whose file the immutability gate forbids touching, so extracting the reader would mean
 * editing it. This is the SECOND occurrence, not the third (project DRY rule: extract on the third
 * repetition) — when a third settlement resource appears, promote the reader to a non-{@code V*}
 * helper in this package and wire the new migrations onto it, leaving V171's frozen copy alone.
 */
public class V175__backfill_settlement_hromadas extends BaseJavaMigration {

    private static final Logger log =
            LoggerFactory.getLogger(V175__backfill_settlement_hromadas.class);

    /** Classpath location of the generated hromada map. */
    static final String CSV_RESOURCE = "db/data/settlement_hromadas.csv";

    /**
     * Expected data-row count — one per free settlement, the same set V171 imported. Pinned so a
     * truncated or hand-edited CSV aborts the migration instead of leaving part of the table
     * unlabelled, which would silently re-open the very ambiguity this phase closes.
     */
    private static final int EXPECTED_ROWS = 25_697;

    /**
     * Expected {@code ambiguous_in_oblast} count across the whole table. All 6 103 are CSV rows:
     * Kyiv is category K, is not in the CSV, and its name is unique within its own oblast, so it
     * keeps V174's {@code DEFAULT FALSE}.
     */
    private static final int EXPECTED_AMBIGUOUS = 6_103;

    /**
     * Expected rows carrying a hromada: every settlement except the two exclusion-zone cities
     * (Прип'ять, Чорнобиль), whose KATOTTH {@code level_3} is the Київська OBLAST rather than a
     * category-H hromada. Kyiv itself is not a CSV row and stays NULL, so the table total is the
     * same number.
     */
    private static final int EXPECTED_WITH_HROMADA = 25_695;

    /**
     * The trigram index V173 created over {@code cities.name_uk} and the settlement autocomplete's
     * only defence against a full-table {@code similarity()} sweep. Named here because this
     * migration's full-table UPDATE bloats it — see {@link #restoreTrigramIndexHealth(Connection)}.
     */
    private static final String TRIGRAM_INDEX = "idx_cities_name_uk_trgm";

    /**
     * The whole backfill, as ONE statement.
     *
     * <p><b>It was 25 697 single-row {@code UPDATE … WHERE katotth_code = ?} batched 1 000 at a
     * time</b>, and that shape cost 2 325 ms of this migration's 2 496 ms — only 171 ms of it was
     * the index rebuild below. 16 test classes instantiate a PostgreSQL container and every one of
     * them replays the whole chain, so the per-row shape was ~40 s of every full suite run. The
     * set-based form measures 597 ms for the identical write.
     *
     * <p>The three arrays are positional: element {@code i} of each is one CSV row. PostgreSQL's
     * multi-argument {@code unnest} zips them into rows, so the join column and both written
     * columns stay bound to the same settlement without a temporary table or a server-side type.
     */
    private static final String UPDATE_SQL = """
            UPDATE cities c
               SET hromada_name_uk     = v.hromada,
                   ambiguous_in_oblast = v.ambiguous
              FROM unnest(?::text[], ?::text[], ?::boolean[]) AS v(code, hromada, ambiguous)
             WHERE c.katotth_code = v.code
            """;

    /**
     * Failure-path diagnostic only — never issued by a green run. The set-based UPDATE reports a
     * row COUNT and not which codes were missed, so {@link #applyAll} re-asks the database for the
     * names once it already knows something is wrong. Capped, because a drifted file could easily
     * miss thousands and the operator needs the first few, not all of them.
     */
    private static final String UNMATCHED_SQL = """
            SELECT v.code
              FROM unnest(?::text[]) AS v(code)
              LEFT JOIN cities c ON c.katotth_code = v.code
             WHERE c.katotth_code IS NULL
             ORDER BY v.code
             LIMIT 5
            """;

    /**
     * One CSV data row.
     *
     * @param katotthCode   stable settlement key, joined against {@code cities.katotth_code}
     * @param hromadaNameUk bare hromada adjective, or {@code null} where the settlement has no
     *                      category-H parent (the CSV writes an empty field for that)
     * @param ambiguous     whether another free settlement shares this row's name within its oblast
     */
    record HromadaRow(String katotthCode, String hromadaNameUk, boolean ambiguous) {}

    @Override
    public Integer getChecksum() {
        CRC32 crc = new CRC32();
        crc.update(readResourceBytes());
        return (int) crc.getValue();
    }

    @Override
    public void migrate(Context context) throws Exception {
        List<HromadaRow> rows = parseCsv();
        if (rows.size() != EXPECTED_ROWS) {
            throw new IllegalStateException(
                    "V175 blocked: " + CSV_RESOURCE + " holds " + rows.size()
                            + " data rows, expected " + EXPECTED_ROWS
                            + ". Regenerate it with scripts/locality/build_settlement_import.py "
                            + "and ship the correction as the next migration version.");
        }

        Connection connection = context.getConnection(); // owned by Flyway — never closed here
        applyAll(connection, rows);
        verifyFinalState(connection);
        restoreTrigramIndexHealth(connection);

        log.info("V175: labelled {} settlements with their hromada ({} ambiguous within oblast)",
                rows.size(), EXPECTED_AMBIGUOUS);
    }

    /**
     * Rebuilds {@code idx_cities_name_uk_trgm} and re-collects statistics, because the UPDATE above
     * is a FULL-TABLE rewrite of 25 697 rows and leaves the trigram index unusable in practice.
     *
     * <p><b>This is not housekeeping — it is the migration paying for its own damage.</b> Every
     * updated row is a new heap tuple, so the heap roughly doubles (3 252 kB -> 6 992 kB) and the
     * GIN index bloats far worse (1 976 kB -> 5 488 kB, measured on postgres:16-alpine through the
     * real Flyway chain on 2026-09-23) with dead entries the pending-list merge never reclaims on
     * its own. An index at ~2.8x its rebuilt size is one the planner prices accordingly, and on a
     * throwaway PostgreSQL 16 loaded from the same CSVs «іванівка» — a benign 8-character keystroke
     * — flipped from an index scan to a Seq Scan of the whole table. That is exactly the
     * unauthenticated denial-of-service vector Phase 326 closed on {@code GET /api/v1/settlements},
     * a {@code permitAll} endpoint with a 240-per-60 s per-IP budget, re-opened by a data migration
     * rather than by an input.
     *
     * <p><b>The Seq Scan flip is real but NOT reproducible on demand, and nothing here may depend on
     * it.</b> Re-measured on 2026-09-23 against the Testcontainers chain with this method's call
     * removed: the bloated index still served every benign and every admitted adversarial term as an
     * IndexScan, with recheck counts identical to the rebuilt-index run, both before and after a
     * {@code VACUUM ANALYZE}. Whether the planner tips is a function of statistics, autovacuum
     * timing and the container's page cache; whether the index is bloated is not. The size is the
     * fact; the plan is a symptom that sometimes shows.
     *
     * <p><b>{@code ANALYZE} alone makes it WORSE, so the V120/V164/V167 convention does not apply
     * here.</b> With fresh statistics over the bloated index the planner reads the index as more
     * expensive still and abandons it for MORE terms, not fewer: benign Seq Scans went 1 -> 3
     * («нов» joined «іванівка»), and the cost budget fell from 81 632 work units to 3 870 against a
     * worst admitted adversarial term of 67 150. The REINDEX is the load-bearing half; the ANALYZE
     * is only correct once it runs after it.
     *
     * <p><b>Why not {@code VACUUM}.</b> {@code VACUUM} cannot run inside a transaction block, and
     * V175 is deliberately transactional so a mid-batch abort rolls the whole backfill back
     * (fail-closed). Plain {@code REINDEX INDEX} — not {@code CONCURRENTLY} — is legal inside one,
     * so the index rebuild joins the migration's own transaction and the fail-closed property is
     * kept. The table is locked for the rebuild; on a 25 697-row table that is milliseconds, and
     * the migration already holds write locks on every one of those rows.
     *
     * <p><b>Which is why this rebuild inherits its own dead tuples, and why
     * {@code V176__vacuum_cities_after_hromada_backfill} exists.</b> Running inside this
     * transaction, the REINDEX cannot see the 26 041 dead tuples the UPDATE above just created as
     * removable, so it rebuilds OVER them: 1 976 kB against 1 176 kB when a {@code VACUUM} precedes
     * it, with the heap left at 6 816 kB against 3 712 kB. Production self-heals — 26 041 dead
     * tuples is far past the autovacuum threshold for this table, so it fires within a naptime —
     * but every Testcontainers replay and the first minute after a deploy pay ~+95 % buffer reads
     * on ordinary autocomplete terms. V176 is non-transactional precisely so it can {@code VACUUM}
     * first and rebuild clean.
     *
     * <p><b>This REINDEX is NOT made redundant by V176 and must not be deleted.</b> V176 is a
     * separate migration and can be skipped, reordered, or fail on a database where V175 succeeded;
     * the settlement autocomplete is a {@code permitAll} endpoint and may not be left depending on
     * a LATER migration for a usable index. V175 leaves a usable index on its own; V176 makes it a
     * compact one.
     *
     * <p><b>Guarded twice, and only one of the two actually discriminates.</b>
     * {@code SettlementSearchCostGuardIT} measures the post-migration PLAN for both the adversarial
     * corpus and the benign controls — but that assertion was falsified on 2026-09-23: with this
     * method's call removed, every admitted and benign term still planned as an IndexScan with
     * byte-identical recheck counts, before and after a {@code VACUUM ANALYZE}. The plan is a
     * statistics- and autovacuum-dependent reading and it does not reliably report this defect. What
     * does is
     * {@code SettlementSearchCostGuardIT.should_keepTheTrigramIndexCompact_when_theMigrationsHaveApplied}
     * which asserts the ARTEFACT: {@code pg_relation_size} of this index, 2 023 424 B with the
     * rebuild against 5 619 712 B without it. {@code VACUUM} cannot shrink a GIN — only
     * {@code REINDEX} can — so that reading cannot race autovacuum. Keep both; the plan assertion
     * covers a different failure (the index dropped, renamed or made unusable).
     *
     * <p>Package-private, not private, for the same reason as the CSV seams above: the statement
     * ORDER here is load-bearing (ANALYZE after REINDEX, never instead of it) and is invisible to
     * any post-migration measurement, so {@code V175BackfillSettlementHromadasTest} drives it
     * directly. That test cannot see this method being CALLED from {@link #migrate}; the size
     * assertion in the IT is what covers that.
     */
    void restoreTrigramIndexHealth(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("REINDEX INDEX " + TRIGRAM_INDEX);
            statement.execute("ANALYZE cities");
        }
        log.info("V175: rebuilt {} and re-analyzed cities after the full-table UPDATE",
                TRIGRAM_INDEX);
    }

    // ------------------------------------------------------------------ CSV

    byte[] readResourceBytes() {
        try (InputStream in = resourceStream()) {
            return in.readAllBytes();
        } catch (Exception e) {
            throw new IllegalStateException("V175: cannot read " + CSV_RESOURCE, e);
        }
    }

    private InputStream resourceStream() {
        InputStream in = getClass().getClassLoader().getResourceAsStream(CSV_RESOURCE);
        if (in == null) {
            throw new IllegalStateException(
                    "V175 blocked: " + CSV_RESOURCE + " is not on the classpath. It is generated "
                            + "by scripts/locality/build_settlement_import.py.");
        }
        return in;
    }

    List<HromadaRow> parseCsv() throws Exception {
        List<HromadaRow> rows = new ArrayList<>(EXPECTED_ROWS);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resourceStream(), StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (header == null || !header.startsWith("\"katotth_code\"")) {
                throw new IllegalStateException(
                        "V175 blocked: unexpected header in " + CSV_RESOURCE + ": " + header);
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
     *
     * @param line       one raw CSV data line
     * @param lineNumber 1-based line number, for the abort message
     * @return the line's fields, unquoted
     */
    List<String> splitQuoted(String line, int lineNumber) {
        List<String> fields = new ArrayList<>(3);
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
                "V175 blocked: malformed " + CSV_RESOURCE + " at line " + lineNumber + " — " + detail);
    }

    /**
     * Maps one split line, translating the empty hromada field to {@code null}.
     *
     * <p>An ambiguous row with no hromada is refused HERE as well as by
     * {@code chk_cities_hromada_disambiguates}, because the constraint's message names a table and
     * a constraint while this one names the offending KATOTTH code and the file that produced it —
     * which is what the operator needs to act on.
     *
     * @param fields     the split fields of one line
     * @param lineNumber 1-based line number, for the abort message
     * @return the parsed row
     */
    HromadaRow toRow(List<String> fields, int lineNumber) {
        if (fields.size() != 3) {
            throw malformed(lineNumber, "expected 3 columns, got " + fields.size());
        }
        String hromada = fields.get(1).isEmpty() ? null : fields.get(1);
        boolean ambiguous = parseStrictBoolean(fields.get(2), lineNumber);
        if (ambiguous && hromada == null) {
            throw malformed(lineNumber, "settlement " + fields.get(0)
                    + " is flagged ambiguous_in_oblast but carries no hromada, so nothing can "
                    + "disambiguate it (chk_cities_hromada_disambiguates would reject the row)");
        }
        return new HromadaRow(fields.get(0), hromada, ambiguous);
    }

    /**
     * Reads the {@code ambiguous_in_oblast} field, accepting only the two tokens the generator
     * emits.
     *
     * <p>{@link Boolean#parseBoolean} was used here and is silently total: it maps EVERY token that
     * is not {@code "true"} onto {@code false}, so a typo is indistinguishable from a deliberate
     * {@code "false"}. That is not a theoretical defect — the two post-condition counts in
     * {@link #verifyFinalState} are totals, so they NET OUT. Hand-editing {@code "true"} to
     * {@code "ture"} on one row and {@code "false"} to {@code "true"} on another still lands 6 103
     * ambiguous rows, still passes every check, and the first settlement quietly loses its
     * disambiguator — the exact ambiguity this phase exists to remove, restored with no error
     * anywhere. Every other field in this reader already refuses what it does not recognise
     * (unquoted field, unterminated field, missing delimiter, wrong column count); this one now
     * matches that strictness.
     *
     * @param field      the raw third field
     * @param lineNumber 1-based line number, for the abort message
     * @return the parsed flag
     */
    private boolean parseStrictBoolean(String field, int lineNumber) {
        return switch (field) {
            case "true" -> true;
            case "false" -> false;
            default -> throw malformed(lineNumber, "ambiguous_in_oblast is \"" + field
                    + "\", expected exactly \"true\" or \"false\" (anything else would be read as "
                    + "false and silently strip a settlement's disambiguator)");
        };
    }

    // ----------------------------------------------------------------- JDBC

    /**
     * Writes both columns for every CSV row in one set-based statement, aborting unless the write
     * touched exactly one {@code cities} row per CSV row.
     *
     * <p><b>THE ROW-COUNT CHECK IS THE OCCUPIED-TERRITORY GUARD, NOT A SANITY CHECK.</b> It is what
     * makes this migration structurally incapable of INSERTing a settlement: it can only ever
     * UPDATE rows V171 already imported, and V171's list is filtered against
     * {@code docs/qa/occupied-settlements.md}. A code in this CSV that matches no {@code cities}
     * row is either a settlement the exclusion set removed on purpose or a genuine drift between
     * the two generated files; both must stop the release, and neither may be allowed to become a
     * new row. The per-row shape this replaced enforced that by aborting on a zero-row batch
     * result. The set-based form enforces the same property through the statement's affected-row
     * count, which is strictly STRONGER: a repeated {@code katotth_code} updates its
     * {@code cities} row once, so the total falls short and the migration aborts, where the
     * per-row loop counted it twice and passed. Do not relax it to {@code <=} or to a warning.
     *
     * <p>{@code executeUpdate()}'s count rather than {@code RETURNING 1}: they report the same
     * number (PostgreSQL's command tag counts exactly the rows the UPDATE touched), and
     * {@code RETURNING} would additionally ship 25 697 rows back over the wire for a figure the
     * driver already has.
     *
     * <p>A shortfall means a settlement is left unlabelled — indistinguishable at read time from
     * one that is genuinely unambiguous, which is the bug this phase exists to remove — so the
     * abort message names the offending codes via {@link #unmatchedCodes}.
     *
     * @param connection Flyway's connection; never closed here
     * @param rows       every parsed CSV row
     * @return the number of {@code cities} rows written
     */
    int applyAll(Connection connection, List<HromadaRow> rows) throws Exception {
        String[] codes = rows.stream().map(HromadaRow::katotthCode).toArray(String[]::new);
        String[] hromadas = rows.stream().map(HromadaRow::hromadaNameUk).toArray(String[]::new);
        Boolean[] ambiguous = rows.stream().map(HromadaRow::ambiguous).toArray(Boolean[]::new);

        int written;
        try (PreparedStatement statement = connection.prepareStatement(UPDATE_SQL)) {
            statement.setArray(1, connection.createArrayOf("text", codes));
            // A null element becomes SQL NULL, never the string "null" — the same property the
            // per-row form got from setNull(1, Types.VARCHAR).
            statement.setArray(2, connection.createArrayOf("text", hromadas));
            statement.setArray(3, connection.createArrayOf("boolean", ambiguous));
            written = statement.executeUpdate();
        }
        if (written != rows.size()) {
            throw new IllegalStateException(driftMessage(connection, codes, written, rows.size()));
        }
        return written;
    }

    /** Builds the abort message for a short write. Failure path only. */
    private String driftMessage(Connection connection, String[] codes, int written, int expected)
            throws Exception {
        return "V175 blocked: the backfill wrote " + written + " `cities` rows for " + expected
                + " rows of " + CSV_RESOURCE + " — " + unmatchedCodes(connection, codes)
                + ". That file and V171's settlements.csv have drifted — regenerate BOTH with "
                + "scripts/locality/build_settlement_import.py and ship the correction as the "
                + "next migration version.";
    }

    /**
     * Names up to five CSV codes with no {@code cities} row, so the operator gets a settlement
     * rather than a bare count. Failure path only — {@link #applyAll} calls it after it already
     * knows the write fell short.
     */
    private String unmatchedCodes(Connection connection, String[] codes) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(UNMATCHED_SQL)) {
            statement.setArray(1, connection.createArrayOf("text", codes));
            try (ResultSet rs = statement.executeQuery()) {
                List<String> unmatched = new ArrayList<>();
                while (rs.next()) {
                    unmatched.add(rs.getString(1));
                }
                return unmatched.isEmpty()
                        ? "every code matched a `cities` row, so the file repeats a katotth_code"
                        : "settlement(s) " + String.join(", ", unmatched)
                                + " match no `cities` row";
            }
        }
    }

    /**
     * Post-conditions asserted against the real table, not against the parsed file — a count that
     * only ever reads the CSV proves nothing about what landed.
     */
    private void verifyFinalState(Connection connection) throws Exception {
        long ambiguous = scalar(connection,
                "SELECT COUNT(*) FROM cities WHERE ambiguous_in_oblast");
        if (ambiguous != EXPECTED_AMBIGUOUS) {
            throw new IllegalStateException(
                    "V175 blocked: " + ambiguous + " rows are flagged ambiguous_in_oblast, expected "
                            + EXPECTED_AMBIGUOUS + ".");
        }

        long labelled = scalar(connection,
                "SELECT COUNT(*) FROM cities WHERE hromada_name_uk IS NOT NULL");
        if (labelled != EXPECTED_WITH_HROMADA) {
            throw new IllegalStateException(
                    "V175 blocked: " + labelled + " rows carry a hromada, expected "
                            + EXPECTED_WITH_HROMADA + " (every settlement except the two "
                            + "exclusion-zone cities; Kyiv is not a CSV row).");
        }

        long undisambiguated = scalar(connection,
                "SELECT COUNT(*) FROM cities WHERE ambiguous_in_oblast AND hromada_name_uk IS NULL");
        if (undisambiguated != 0) {
            throw new IllegalStateException(
                    "V175 blocked: " + undisambiguated + " ambiguous row(s) carry no hromada. "
                            + "chk_cities_hromada_disambiguates should have made this "
                            + "unreachable — verify V174 applied.");
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
