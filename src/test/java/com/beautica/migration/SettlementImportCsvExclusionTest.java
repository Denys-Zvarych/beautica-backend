package com.beautica.migration;

import com.beautica.support.OccupiedSettlementCodes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Container-free guard on {@code db/data/settlements.csv} - the SOURCE half of Phase 325's
 * occupied-territory contract.
 *
 * <p>Phase 325 D3: occupied settlements are never imported, because the exclusion set is applied to
 * the import source rather than stored as a column a future query could forget to filter on. That
 * makes the CSV itself the compliance artefact, and this test the assertion on it.
 *
 * <p><b>Why a second test, when {@code LocalityTaxonomySeedMigrationTest} asserts the same set
 * against the migrated table:</b> the two fail for different reasons and at different costs. This
 * one needs no Postgres, no Spring context and no Flyway run, so a poisoned CSV is caught in
 * milliseconds by anyone who runs a single test class - including a developer who regenerates the
 * file with {@code scripts/locality/build_settlement_import.py} and never boots a container. The
 * integration sibling proves what actually LANDED, which a file-only check can never prove (V171
 * could upsert something the CSV does not contain, and V170's deletes are invisible here).
 * Source-clean and table-clean are separate claims; both are asserted.
 *
 * <p>Deliberately NOT a {@code @SpringBootTest}: there is nothing to wire. Loading a Testcontainers
 * Postgres to read two classpath files would be the anti-pattern the QA playbook bans outright.
 */
@DisplayName("settlements.csv - Phase 324 occupied-settlement exclusion (container-free)")
class SettlementImportCsvExclusionTest {

    private static final String CSV_RESOURCE = "db/data/settlements.csv";

    /**
     * Data rows in the shipped CSV, mirroring {@code V171__import_free_settlements.EXPECTED_ROWS}.
     * Pinned here too so this test cannot pass against an empty file: an exclusion assertion over
     * zero rows is vacuously true, which is exactly the shape of guard that never goes red.
     */
    private static final int EXPECTED_CSV_ROWS = 25_697;

    @Test
    @DisplayName("holds no code from the Phase 324 exclusion set - 2 958 occupied settlements, none offered to the importer")
    void should_holdNoCodeFromThePhase324ExclusionSet_when_csvGenerated() {
        List<String> excluded = OccupiedSettlementCodes.load();
        List<String> csvCodes = csvKatotthCodes();

        assertThat(csvCodes)
                .as("the CSV must carry every free settlement - an exclusion check over a "
                        + "truncated file proves nothing")
                .hasSize(EXPECTED_CSV_ROWS);

        Set<String> excludedSet = new HashSet<>(excluded);
        List<String> leaked = csvCodes.stream().filter(excludedSet::contains).toList();

        assertThat(leaked)
                .as("settlements.csv is the import SOURCE and Phase 325 D3 makes it the only place "
                        + "the occupied-territory ban is enforced - there is no occupation_status "
                        + "column and no runtime predicate downstream. Any code here reaches "
                        + "`cities` and is offered to users. Leaked: %s", leaked)
                .isEmpty();
    }

    @Test
    @DisplayName("the exclusion resource is armed - exactly 2 958 codes, none of them Crimea or Sevastopol prefixed")
    void should_loadTheFullExclusionSet_when_guardResourceRead() {
        // Guards the guard. A resource that silently shrank would turn the assertion above into a
        // filter over a near-empty set and stay green while 2 900 codes went unchecked.
        List<String> excluded = OccupiedSettlementCodes.load();

        assertThat(excluded)
                .as("Phase 324 publishes 2 958 occupied settlements in "
                        + "docs/qa/occupied-settlements.md")
                .hasSize(OccupiedSettlementCodes.EXPECTED_CODE_COUNT);
        assertThat(excluded)
                .as("UA01 (Crimea) and UA85 (Sevastopol) are excluded WHOLESALE at oblast level "
                        + "(D1) and are not settlement-level entries - a code under those prefixes "
                        + "here means the two filters have been conflated")
                .noneMatch(code -> code.startsWith("UA01") || code.startsWith("UA85"));
    }

    @Test
    @DisplayName("every CSV code is distinct - ON CONFLICT DO UPDATE would otherwise mask a duplicated settlement")
    void should_holdOnlyDistinctKatotthCodes_when_csvGenerated() {
        // V171 upserts ON CONFLICT (katotth_code) DO UPDATE, so a duplicate row imports as a
        // silent overwrite: the final row count still matches EXPECTED_ROWS only because the
        // migration compares against the CSV's own line count, and the table would end up one
        // settlement short with no error anywhere.
        List<String> csvCodes = csvKatotthCodes();
        Set<String> distinct = new LinkedHashSet<>(csvCodes);

        assertThat(distinct)
                .as("a repeated katotth_code is absorbed by the upsert and loses a real settlement")
                .hasSameSizeAs(csvCodes);
    }

    private List<String> csvKatotthCodes() {
        List<String> codes = new ArrayList<>(EXPECTED_CSV_ROWS);
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(CSV_RESOURCE)) {
            assertThat(in)
                    .as("%s must be on the test classpath - it is the shipped import source",
                            CSV_RESOURCE)
                    .isNotNull();
            try (BufferedReader reader =
                         new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String header = reader.readLine();
                assertThat(header)
                        .as("the generator writes a QUOTE_ALL header; a changed shape means this "
                                + "reader is parsing the wrong column")
                        .startsWith("\"katotth_code\"");
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        continue;
                    }
                    codes.add(firstQuotedField(line));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read " + CSV_RESOURCE, e);
        }
        return codes;
    }

    /** First field of a QUOTE_ALL line. KATOTTH codes never contain a quote, so this is total. */
    private String firstQuotedField(String line) {
        int close = line.indexOf('"', 1);
        if (!line.startsWith("\"") || close < 0) {
            throw new IllegalStateException("Malformed " + CSV_RESOURCE + " line: " + line);
        }
        return line.substring(1, close);
    }
}
