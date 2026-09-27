package com.beautica.migration;

import com.beautica.support.OccupiedSettlementCodes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ties {@code src/test/resources/locality/occupied-katotth-codes.txt} to its source of truth,
 * {@code docs/qa/occupied-settlements.md} (Phase 325 QA LOW).
 *
 * <p><b>The gap this closes.</b> Both files list the same 2 958 occupied settlements, and the
 * only thing that linked them was the number 2 958 appearing in both. That is not a link: an
 * Order 376 amendment routinely de-occupies some settlements and occupies others in the same
 * revision, so membership changes while the count does not — and the guard would have stayed
 * green while asserting the absence of the wrong set. Worse, the two files live in DIFFERENT git
 * repositories ({@code beautica-backend} is standalone), so the backend's own CI never checks
 * out the markdown and cannot diff against it.
 *
 * <p><b>How it is closed.</b> {@code scripts/locality/derive_occupied_set.py} now emits both
 * files from one derivation and stamps the SHA-256 of the newline-joined sorted code set into the
 * resource's {@code # codes-sha256:} header; its {@code --check} mode diffs both. This test
 * recomputes that digest from the codes actually present — an independent implementation, not a
 * call into {@link OccupiedSettlementCodes} — so a hand-edited code, a dropped line or a
 * copy-pasted stale header fails here, in the backend repo, with no access to the source
 * document.
 *
 * <p>Plain JUnit: this asserts a property of a committed file, so neither Spring nor Postgres is
 * involved (§M-1).
 */
@DisplayName("occupied-katotth-codes.txt <-> docs/qa/occupied-settlements.md, and no tracked data file carries its codes")
class OccupiedSettlementCodesGuardTest {

    private static final String DIGEST_HEADER = "# codes-sha256:";

    @Test
    @DisplayName("loads exactly the 2 958 codes of Phase 324's exclusion set")
    void should_loadTheWholeExclusionSet_when_theResourceIsIntact() {
        List<String> codes = OccupiedSettlementCodes.load();

        assertThat(codes)
                .hasSize(OccupiedSettlementCodes.EXPECTED_CODE_COUNT)
                .doesNotHaveDuplicates()
                .allMatch(code -> code.matches("UA[0-9]{17}"));
    }

    @Test
    @DisplayName("the declared codes-sha256 header matches an independent digest of the codes present")
    void should_matchTheDeclaredDigest_when_theResourceHasNotBeenHandEdited() {
        List<String> lines = readResourceLines();
        String declared = lines.stream()
                .filter(line -> line.startsWith(DIGEST_HEADER))
                .map(line -> line.substring(DIGEST_HEADER.length()).trim())
                .findFirst()
                .orElse("");
        List<String> codes = lines.stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .sorted()
                .distinct()
                .toList();

        assertThat(declared)
                .as("the header is the only cross-repo tie to docs/qa/occupied-settlements.md — "
                        + "without it the resource is unverifiable from this repository")
                .isNotEmpty();
        assertThat(sha256(String.join("\n", codes) + "\n"))
                .as("recomputed from the %d codes present; a mismatch means the resource was "
                        + "hand-edited instead of re-derived with "
                        + "scripts/locality/derive_occupied_set.py", codes.size())
                .isEqualTo(declared);
    }

    @Test
    @DisplayName("the header's declared count agrees with the codes present")
    void should_declareTheCountItActuallyHolds_when_theResourceIsIntact() {
        List<String> lines = readResourceLines();
        String declaredCount = lines.stream()
                .filter(line -> line.startsWith("# count:"))
                .map(line -> line.substring("# count:".length()).trim())
                .findFirst()
                .orElse("");
        long codes = lines.stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .distinct()
                .count();

        assertThat(declaredCount).isEqualTo(String.valueOf(codes));
    }

    /**
     * Files allowed to carry exclusion-set codes, each pinned to its EXACT number of DISTINCT
     * codes. Every one is either the exclusion set itself or a purge/guard that must name the
     * codes it removes or proves absent — V53 seeded 17 occupied cities in error (an applied
     * migration, immutable), V170 deleted them, V179 cleared the text they left behind, and the
     * tests prove each step. A count moving in EITHER direction fails: up means occupied data
     * leaked into a file that was allowed only a known purge list; down means a guard silently
     * lost coverage.
     */
    private static final Map<String, Integer> ALLOWED_CODE_COUNTS = Map.of(
            "src/test/resources/locality/occupied-katotth-codes.txt", OccupiedSettlementCodes.EXPECTED_CODE_COUNT,
            "src/main/resources/db/migration/V53__seed_locality_taxonomy.sql", 17,
            "src/main/resources/db/migration/V170__widen_cities_to_full_settlement_taxonomy.sql", 17,
            "src/main/resources/db/migration/V179__clear_occupied_city_text_left_by_v170.sql", 17,
            "src/test/java/com/beautica/migration/LocalityTaxonomySeedMigrationTest.java", 17,
            "src/test/java/com/beautica/migration/V170OccupiedCityPurgeGuardTest.java", 17,
            "src/test/java/db/migration/V171ImportFreeSettlementsTest.java", 1,
            "src/test/java/db/migration/V175BackfillSettlementHromadasTest.java", 1);

    /**
     * Tier-1 tripwire (memory: occupied-territory data ban). A raw classifier snapshot that was
     * only OBLAST-prefix-filtered ({@code katottg-2025-05-16.json}) sat tracked for months carrying
     * 1 117 codes of this exclusion set — nothing checked repository files against the SET.
     *
     * <p>Scans EVERY file git would commit — tracked plus untracked-but-not-ignored
     * ({@code git ls-files --cached --others --exclude-standard}), so work in progress is covered
     * before it lands while an ignored local copy of a raw snapshot (kept on disk as tooling
     * input) never trips it. That spans src/, docs/, scripts/ and every other top-level
     * directory. Files are read as ISO-8859-1, so a code is found in ANY file, text or binary,
     * without a decode failure silently skipping it. Match is by exact 19-character KATOTTH code,
     * never by name substring (Кримне / Луганське in serviced oblasts are not occupied data).
     */
    @Test
    @DisplayName("no repository file carries an exclusion-set code except the pinned purge/guard files, at their exact counts")
    void should_findOnlyPinnedExclusionSetCodes_when_everyRepositoryFileIsScanned() throws Exception {
        Set<String> excluded = new HashSet<>(OccupiedSettlementCodes.load());
        List<String> files = repositoryFiles();
        Map<String, Integer> actualCounts = new TreeMap<>();

        for (String file : files) {
            Path path = Path.of(file);
            if (!Files.isRegularFile(path)) {
                continue; // deleted in the working tree but still in the index
            }
            String content = Files.readString(path, StandardCharsets.ISO_8859_1);
            Matcher code = KATOTTH_CODE.matcher(content);
            Set<String> hits = new HashSet<>();
            while (code.find()) {
                if (excluded.contains(code.group())) {
                    hits.add(code.group());
                }
            }
            if (!hits.isEmpty()) {
                actualCounts.put(file, hits.size());
            }
        }

        assertThat(files)
                .as("the scan must actually cover the settlement import CSV and this guard's own "
                        + "resource — otherwise `git ls-files` saw nothing and the guard passes vacuously")
                .contains("src/main/resources/db/data/settlements.csv",
                        "src/test/resources/locality/occupied-katotth-codes.txt");
        assertThat(actualCounts)
                .as("files carrying occupied-settlement codes (distinct count per file). An UNLISTED "
                        + "file: remove it from git (raw snapshot) or re-derive it through the Phase 324 "
                        + "exclusion set — never commit occupied-territory data. A LISTED file whose "
                        + "count moved: re-verify it and update ALLOWED_CODE_COUNTS deliberately")
                .isEqualTo(new TreeMap<>(ALLOWED_CODE_COUNTS));
    }

    private static final Pattern KATOTTH_CODE = Pattern.compile("UA[0-9]{17}");

    private static List<String> repositoryFiles() throws Exception {
        Process git = new ProcessBuilder("git", "ls-files", "-z", "--cached", "--others", "--exclude-standard")
                .redirectErrorStream(false)
                .start();
        String out = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(git.waitFor())
                .as("`git ls-files` must succeed from the project directory — without it the guard "
                        + "cannot tell a committable file from an ignored local snapshot")
                .isZero();
        return Arrays.stream(out.split("\0")).filter(f -> !f.isBlank()).toList();
    }

    private static List<String> readResourceLines() {
        List<String> lines = new ArrayList<>();
        try (InputStream in = OccupiedSettlementCodesGuardTest.class.getClassLoader()
                .getResourceAsStream(OccupiedSettlementCodes.RESOURCE)) {
            assertThat(in)
                    .as("%s must be on the test classpath", OccupiedSettlementCodes.RESOURCE)
                    .isNotNull();
            try (BufferedReader reader =
                         new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("cannot read " + OccupiedSettlementCodes.RESOURCE, e);
        }
        return lines;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable on this JVM", e);
        }
    }
}
