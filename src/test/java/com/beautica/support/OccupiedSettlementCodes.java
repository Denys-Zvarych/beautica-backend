package com.beautica.support;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The ONE place a test loads Phase 324's occupied-settlement exclusion set.
 *
 * <p>Phase 325 D3 resolved that occupied settlements are filtered out of the import SOURCE and
 * never enter the database: there is deliberately no {@code occupation_status} column and no
 * runtime predicate. That makes the ban an ABSENCE invariant, and an absence invariant with no
 * test is indistinguishable from a bug. Before this class the guard covered only the
 * {@code UA01}/{@code UA85} prefixes plus the 17 cities V53 seeded by mistake - 2 941 of the 2 958
 * codes were unasserted.
 *
 * <p>{@value #RESOURCE} is committed code-only and in Latin script on purpose. The repo's
 * occupied-territory data ban forbids shipping the settlement NAMES; it permits - and this phase
 * requires - a code-only absence-guard. The codes are opaque KATOTTH identifiers, and they exist
 * here to prove rows are missing, never to select rows.
 *
 * <p>{@link #load()} is self-defending: it fails if the resource is absent, it pins the exact
 * cardinality, and it verifies the {@code # codes-sha256:} header against the codes it just read.
 * Without those a truncated or emptied file would turn every consuming assertion into a vacuous
 * {@code IN ()} that can never go red - the classic green-because-it-never-ran failure mode.
 *
 * <p><b>The digest is the tie to the source of truth</b> (Phase 325 QA LOW). {@value #RESOURCE}
 * and {@code docs/qa/occupied-settlements.md} were linked by nothing but the number 2 958
 * appearing in both, so a re-derivation that changed WHICH settlements are occupied without
 * changing HOW MANY - the ordinary outcome of an Order 376 amendment, where some places are
 * de-occupied and others newly occupied - would leave this guard green while asserting the wrong
 * set. Both files are now emitted by one run of
 * {@code scripts/locality/derive_occupied_set.py}, whose {@code --check} mode diffs both, and
 * that script stamps the SHA-256 of the newline-joined sorted code set into this file's header.
 * {@code beautica-backend} is a separate git repository, so a backend test cannot read the
 * markdown; the header is what carries the tie across that boundary - a hand-edited code fails
 * the digest here, in the backend's own CI, with no access to the source document.
 */
public final class OccupiedSettlementCodes {

    /** Classpath location of the code-only exclusion list. */
    public static final String RESOURCE = "locality/occupied-katotth-codes.txt";

    /**
     * Exact number of codes in Phase 324's set, as published by
     * {@code docs/qa/occupied-settlements.md}. Pinned so a truncated resource fails loudly instead
     * of silently defanging every guard that consumes it.
     */
    public static final int EXPECTED_CODE_COUNT = 2_958;

    /** Header line carrying the digest of the code set, written by the generator. */
    private static final String DIGEST_HEADER = "# codes-sha256:";

    private OccupiedSettlementCodes() {
    }

    /**
     * Reads the exclusion set off the test classpath.
     *
     * @return the 2 958 occupied KATOTTH codes, in file order, with no duplicates
     * @throws IllegalStateException when the resource is missing, malformed, or does not hold
     *         exactly {@link #EXPECTED_CODE_COUNT} distinct codes
     */
    public static List<String> load() {
        Set<String> codes = new LinkedHashSet<>(EXPECTED_CODE_COUNT * 2);
        String declaredDigest = null;
        int lineNumber = 0;
        try (InputStream in = OccupiedSettlementCodes.class.getClassLoader()
                .getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(
                        "Occupied-settlement guard disarmed: " + RESOURCE + " is not on the test "
                                + "classpath. It is the only proof that Phase 324's exclusion set "
                                + "never reached `cities`; without it the ban is untested.");
            }
            try (BufferedReader reader =
                         new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lineNumber++;
                    String trimmed = line.trim();
                    if (trimmed.startsWith(DIGEST_HEADER)) {
                        declaredDigest = trimmed.substring(DIGEST_HEADER.length()).trim();
                        continue;
                    }
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        continue;
                    }
                    if (!trimmed.matches("UA[0-9]{17}")) {
                        throw new IllegalStateException(
                                RESOURCE + " line " + lineNumber + " is not a bare KATOTTH code: "
                                        + trimmed);
                    }
                    codes.add(trimmed);
                }
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read " + RESOURCE, e);
        }

        if (codes.size() != EXPECTED_CODE_COUNT) {
            throw new IllegalStateException(
                    "Occupied-settlement guard disarmed: " + RESOURCE + " holds " + codes.size()
                            + " distinct codes, expected " + EXPECTED_CODE_COUNT
                            + ". A shrunken set silently narrows every exclusion assertion that "
                            + "consumes it. Regenerate it from docs/qa/occupied-settlements.md.");
        }

        verifyDigest(codes, declaredDigest);
        return new ArrayList<>(codes);
    }

    /**
     * Recomputes the code-set digest and compares it to the generator's header.
     *
     * <p>This is the check that ties {@value #RESOURCE} to
     * {@code docs/qa/occupied-settlements.md}. The count alone does not: an Order 376 amendment
     * routinely de-occupies some settlements and occupies others, so membership can change while
     * the total does not.
     *
     * @param codes          the codes just read, in file order
     * @param declaredDigest the value of the {@code # codes-sha256:} header, or {@code null}
     * @throws IllegalStateException when the header is missing or does not match
     */
    private static void verifyDigest(Set<String> codes, String declaredDigest) {
        String actual = sha256OfSortedCodes(codes);
        if (declaredDigest == null || declaredDigest.isEmpty()) {
            throw new IllegalStateException(
                    "Occupied-settlement guard disarmed: " + RESOURCE + " carries no '"
                            + DIGEST_HEADER + "' header. That header is the only link between this "
                            + "resource and docs/qa/occupied-settlements.md, which lives in a "
                            + "different git repository. Regenerate both with "
                            + "`python3 scripts/locality/derive_occupied_set.py --fetch`. "
                            + "Computed digest of the current codes: " + actual);
        }
        if (!declaredDigest.equals(actual)) {
            throw new IllegalStateException(
                    "Occupied-settlement guard disarmed: " + RESOURCE + " was edited without "
                            + "re-deriving it. Declared codes-sha256 " + declaredDigest
                            + " but the " + codes.size() + " codes present hash to " + actual
                            + ". The exclusion set is derived from Order No. 376, never typed by "
                            + "hand: regenerate both files with "
                            + "`python3 scripts/locality/derive_occupied_set.py --fetch`.");
        }
    }

    /**
     * SHA-256 over the newline-joined SORTED code set, with a trailing newline — byte-for-byte
     * the string {@code derive_occupied_set.py#codes_digest} hashes, so the two implementations
     * agree without either reading the other's file.
     */
    private static String sha256OfSortedCodes(Set<String> codes) {
        StringBuilder canonical = new StringBuilder(codes.size() * 20);
        codes.stream().sorted().forEach(code -> canonical.append(code).append('\n'));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                   .append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable on this JVM", e);
        }
    }
}
