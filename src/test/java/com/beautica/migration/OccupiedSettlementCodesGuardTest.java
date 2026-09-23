package com.beautica.migration;

import com.beautica.support.OccupiedSettlementCodes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
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
@DisplayName("occupied-katotth-codes.txt <-> docs/qa/occupied-settlements.md")
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
