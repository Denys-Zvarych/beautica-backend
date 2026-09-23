package com.beautica.support;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * A generated adversarial corpus for the public, unauthenticated free-text surfaces — the input
 * space {@code GET /api/v1/settlements} and {@code GET /api/v1/search/**} have to survive from
 * anyone, with no token.
 *
 * <h3>Why a generated corpus and not more hand-written cases</h3>
 * Two denial-of-service vectors shipped through a green suite on the same endpoint, three weeks
 * apart, and both were found by a human trying inputs by hand:
 *
 * <ol>
 *   <li>{@code "•".repeat(50)} — no alphanumeric, so pg_trgm extracts an EMPTY key set, no GIN
 *       index can be used, and both tiers degrade into a sequential scan of all 25 698 rows
 *       (119 ms against 3.4 ms for «нов»). An existing test fed exactly this input and passed:
 *       it asserted only that the result was empty, which is equally true of "escaped correctly"
 *       and of "scanned the whole table and matched nothing".</li>
 *   <li>{@code "ка ".repeat(17)} — 50 characters, so it clears {@code @Size}; longer than the
 *       3-character floor, so it clears that; and alphanumeric-bearing, so it cleared the guard
 *       added for (1). Every TOKEN is two characters, so the whole term yields three distinct
 *       padded trigram keys, a 0.3 threshold needs one of three to match, and 10 071 candidate
 *       rows each pay a {@code similarity()} recheck — 51.7 ms, 12.3x the worst benign term's
 *       rechecks. (Re-measured 2026-09-23; this read 10 237 when first written.)</li>
 *   <li>{@code "•к".repeat(25)} — found by THIS corpus, not by a person. One 50-character token,
 *       so a per-token floor admits it; alphanumeric throughout, so the guard added for (2) admits
 *       it; and every alphanumeric run is a single letter, so it yields two distinct keys, 3 023
 *       rechecks and 21.7 ms. It is why the admission predicate is now the length of the longest
 *       alphanumeric RUN — the one property padding cannot inflate.</li>
 * </ol>
 *
 * <p>Each fix closed the input in front of it. Enumerating the SHAPE of hostile input instead —
 * fragment alphabet x fragment length x separator x repetition, filled to the {@code @Size}
 * ceiling — is what turns "we patched two strings" into "the next one of this family is already
 * in the suite". The generator is deterministic and seedless on purpose: a randomised corpus that
 * fails once and passes on re-run is not evidence of anything.
 *
 * <h3>How it is consumed</h3>
 * Two readers, deliberately asymmetric:
 * <ul>
 *   <li>{@code SettlementSearchAdmissionGuardTest} (unit, no database) asserts the ADMISSION
 *       decision — which of these the service refuses before issuing any statement.</li>
 *   <li>{@code SettlementSearchCostGuardIT} (integration, real 25 698 rows) takes every term the
 *       service ADMITS and asserts what it costs. That loop is the general guard: a future input
 *       class that slips past the Java controls is caught by what it does to the database, not by
 *       whether someone remembered to add a case for it.</li>
 * </ul>
 */
public final class AdversarialSearchTerms {

    /**
     * The {@code @Size(max = ...)} ceiling on {@code GET /settlements?query=}. Restated rather than
     * imported: the corpus must keep generating terms at the boundary even if the controller
     * constant moves, and a silent re-sync would hide that the boundary moved.
     */
    public static final int MAX_QUERY_LENGTH = 50;

    /**
     * The unit that gets repeated. Ordered roughly by how much a reader would expect it to be
     * harmless: single letters and bigrams are the shapes that survived every length-based control,
     * because a length control measures the term and selectivity comes from the longest TOKEN.
     */
    private static final List<String> FRAGMENTS = List.of(
            // sub-trigram Cyrillic — the «ка »x17 family, at every length below the floor
            "к", "ка", "о", "ов", "і", "ів",
            // exactly at the floor, and just above it
            "ков", "кови",
            // Latin and digits: \p{Alnum} is ASCII-only, so these prove the guard is Unicode-aware
            // in both directions rather than accidentally passing Cyrillic through
            "a", "ab", "abc", "1", "12", "123",
            // zero-trigram runs — vector (1), at every punctuation class measured at 115-119 ms
            "•", "%", "_", "'", ".", "-", "’", "••",
            // punctuation glued to a sub-trigram letter: bears an alphanumeric (clears the
            // trigram guard) while still extracting almost no distinct keys
            "•к", "к•", "'к", "к_");

    /**
     * Separators between repetitions. {@code ""} builds one very long token; {@code " "} builds
     * many short ones — the axis the two shipped vectors sit at opposite ends of. The non-breaking
     * space is here because the normaliser collapses Unicode whitespace, so it must produce the
     * same token split as a plain space; a separator-blind guard would let {@code "ка "}
     * through as a single 50-character token.
     */
    private static final List<String> SEPARATORS = List.of("", " ", " ", "  ");

    /**
     * Terms pinned by hand because they are not a product of the grammar above.
     *
     * <p>The last three are the legitimate DIVERGENCE case: the sibling
     * {@code NormalizedSearchQuery.of} caps at 4 tokens and would report these unservable, while
     * the settlement surface has no token cap and serves them. That is safe rather than a second
     * hole — a real token contributes five distinct trigram keys, which RAISES the 0.3 threshold's
     * minimum-match count and makes the scan MORE selective, not less. Being in the corpus is what
     * turns that sentence from a claim into a measurement in {@code SettlementSearchCostGuardIT}.
     */
    private static final List<String> PINNED = List.of(
            "•".repeat(MAX_QUERY_LENGTH),
            "•".repeat(MAX_QUERY_LENGTH - 1) + "о",
            "ка ".repeat(16) + "ка",
            "•к".repeat(25),
            "а б",
            "а  б",
            "ка ка ка ка Львів",
            "•• •• •• •• львів",
            "ка ка ка ка ка ка ка ка Київ");

    private AdversarialSearchTerms() {
    }

    /**
     * Every generated term, deduplicated, in a stable order.
     *
     * <p>Repetition counts are 1, 2, 3 and "as many as fit under {@link #MAX_QUERY_LENGTH}" rather
     * than every count in between: the interesting transitions are "one token", "a couple" and
     * "the ceiling", and enumerating all of them would multiply the integration loop's EXPLAIN
     * count for terms that differ only in how many identical keys they repeat.
     *
     * @return the corpus; never empty, and its size is asserted by its consumers so a broken
     *         generator cannot quietly turn every loop over it into a no-op
     */
    public static List<String> corpus() {
        LinkedHashSet<String> terms = new LinkedHashSet<>(PINNED);
        for (String fragment : FRAGMENTS) {
            for (String separator : SEPARATORS) {
                String unit = fragment + separator;
                int maxRepeats = MAX_QUERY_LENGTH / unit.length();
                for (int repeats : new int[]{1, 2, 3, maxRepeats}) {
                    if (repeats < 1 || repeats > maxRepeats) {
                        continue;
                    }
                    String term = unit.repeat(repeats);
                    if (term.length() <= MAX_QUERY_LENGTH && !term.isBlank()) {
                        terms.add(term);
                    }
                }
            }
        }
        return List.copyOf(terms);
    }

    /**
     * Benign terms with a known, measured cost — the positive control for any budget asserted over
     * {@link #corpus()}.
     *
     * <p>Without them an over-tight budget and a broken measurement look identical: both make the
     * adversarial loop pass for the wrong reason. «нов» is the documented worst benign 3-character
     * keystroke (1 065 candidate rows), «іван фран» is the two-token acceptance case, and
     * «іванівка» is the worst benign term on the table at 5 102 rechecked rows.
     *
     * <p><b>«іванівка» is NOT expensive because of the 99-row Іванівка duplicate-name cluster</b>,
     * which this javadoc claimed until 2026-09-23. {@code Rows Removed by Index Recheck} counts the
     * candidates the {@code similarity()} recheck REJECTED, so the 99 rows that match are the ones
     * it does not count; deleting every {@code Іванівк%} row leaves the figure at exactly 5 102.
     * The cost is the {@code -івка} trigram neighbourhood — 5 460 settlements end in it. That
     * distinction is load-bearing rather than pedantic: a named cluster reads as a stable landmark,
     * while a morphology is thinned by every regeneration of the exclusion set, and
     * {@code SettlementSearchCostGuardIT}'s budget is a multiple of THIS number. See
     * {@code SettlementSearchCostGuardIT.MIN_WORK_BUDGET} for the floor that now absorbs it.
     */
    public static List<String> benignControls() {
        return List.of("нов", "льв", "іванівка", "іван фран", "кам'янка", "терноп");
    }
}
