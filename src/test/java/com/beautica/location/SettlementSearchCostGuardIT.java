package com.beautica.location;

import com.beautica.AbstractIntegrationTest;
import com.beautica.location.service.SettlementSearchService;
import com.beautica.support.AdversarialSearchTerms;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>COST GUARD — what every term the service ADMITS actually costs the database.</b>
 *
 * <p>The audit this class comes out of had one finding above all the others: two unauthenticated
 * denial-of-service vectors shipped on {@code GET /api/v1/settlements} through a suite that was
 * green, and one of them was fed to an existing test verbatim
 * ({@code SettlementSearchIT.should_treatUnderscoreAsLiteral_when_queryContainsALikeWildcard})
 * which passed, because it asserted SEMANTICS — the result was empty — and an empty result is
 * equally consistent with "escaped correctly" and with "scanned all 25 698 rows and matched nothing".
 * Every control that was then added closed the one input in front of it:
 *
 * <ul>
 *   <li>a 3-character floor on the term -> defeated by {@code "•".repeat(50)}</li>
 *   <li>+ "an alphanumeric appears somewhere" -> defeated by {@code "ка ".repeat(17)}</li>
 *   <li>+ a 3-character floor per TOKEN -> defeated by {@code "•к".repeat(25)}</li>
 * </ul>
 *
 * <p>This class is the guard that does not have to be extended for the next one. It takes the
 * generated corpus in {@link AdversarialSearchTerms}, asks the PRODUCTION predicate which terms it
 * would execute, and measures every one of those against the real V170/V171-migrated table. A
 * future input class that walks past the Java controls is caught here by what it does to the
 * database, not by whether anyone remembered to write a case for it.
 *
 * <h3>The metric, and why it is not elapsed time</h3>
 * {@code similarity()} rechecks x term length. The rechecks are the rows the GIN scan hands to the
 * recheck ({@code EXPLAIN}'s {@code Rows Removed by Index Recheck}) and the length is what each of
 * those evaluations costs, because {@code similarity()} is quadratic-ish in the query term. Both
 * come out of {@code EXPLAIN ANALYZE} as integers off a fixed Flyway seed, so the number is
 * deterministic run to run — measured identical across repeated runs, unlike wall-clock, which on
 * CI is a flake generator. Elapsed times appear in the comments as calibration, never in an
 * assertion.
 *
 * <h3>The budget</h3>
 * {@link #MAX_WORK_RATIO} x the worst BENIGN query measured in the same run, rather than an
 * absolute constant, so the bound tracks the data instead of rotting against it. Measured on the
 * 25 698-row table at the time of writing: worst benign «іванівка» 40 816 work units / 10.9 ms;
 * worst admitted adversarial 67 150 / 5.5 ms; and the two vectors the service now refuses, if they
 * WERE executed, 151 100 / 20.4 ms and 503 500 / 51.7 ms.
 *
 * <p><b>Two margins, and only one of them is headroom.</b> They were conflated in an earlier
 * revision of this javadoc, which quoted the larger one alone:
 * <ul>
 *   <li><b>1.85x — separation.</b> Budget 81 632 -> nearest REFUSED vector 151 100. It says the
 *       budget discriminates: it is below the cheapest thing the Java guard closes, so
 *       readmitting that vector fails this class rather than sliding under the bound. It says
 *       nothing about how close the suite is to going red.</li>
 *   <li><b>1.22x — headroom, and the number that matters when the data changes.</b> Worst
 *       ADMITTED term 67 150 -> budget 81 632. This is the whole distance between green and red.
 *       A re-import that grows the table, or one that shifts the name distribution so the worst
 *       benign control gets CHEAPER (the budget is a multiple of it, so a cheaper control LOWERS
 *       the bound while the adversarial terms hold), consumes that 22% first. Watch this one; the
 *       1.85x is a property of the design and will not move on its own.</li>
 * </ul>
 *
 * <h3>The metric is blind to a sequential scan — which is why there are two tests</h3>
 * {@link #measure(String)} reads {@code Rows Removed by Index Recheck} out of the plan and
 * defaults to {@code 0} when the pattern does not match. A Seq Scan plan has no index recheck to
 * report, so it emits that line and {@code measure} returns ZERO work units: the single most
 * expensive plan this endpoint can produce scores as the cheapest possible one.
 * {@code "•".repeat(50)} — 25 698 {@code similarity()} evaluations, 119 ms, the vector the whole
 * phase exists to close — measures 0. So
 * {@link #should_boundTheCostOfEveryAdmittedTerm_when_fedTheAdversarialCorpus()} would PASS an
 * admitted term that scans the entire table, and
 * {@link #should_neverSequentiallyScan_when_theServiceAdmitsATerm()} is what actually covers that
 * case. The two look like coarse and fine versions of one property and are not: neither subsumes
 * the other, the cheap-looking half of the budget test is precisely the expensive half of reality,
 * and deleting the Seq Scan test as a duplicate would remove the only assertion standing between
 * this endpoint and the plan it was written to prevent.
 *
 * <h3>The Seq Scan test walks the BENIGN controls too, and that is not symmetry for its own sake</h3>
 * It walked {@link AdversarialSearchTerms#corpus()} alone until Phase 327, which encodes the
 * assumption that the planner only ever abandons the index under ATTACK. V175 falsified it: its
 * full-table UPDATE bloated {@code idx_cities_name_uk_trgm} from 1 152 kB to 5 496 kB, and the
 * planner then stopped using it for «нов» — a three-character keystroke, an ORDINARY one, in no
 * adversarial corpus and never going to be in one. The guard was green throughout. What this class
 * measures is a property of the PLAN, and the plan does not care how the term was typed; a Seq Scan
 * on normal traffic is the same 25 698 {@code similarity()} evaluations per request, reachable by
 * anyone typing a city name. So the loop takes both lists, and a benign name in its failure output
 * points at the index rather than at the admission predicate.
 *
 * <h3>The budget has a FLOOR, because a ratio to a control at a different length is a coincidence</h3>
 * {@link Cost#work()} is rechecks x term length, and the two sides of the ratio sit at opposite ends
 * of the length axis: the worst benign control «іванівка» is 8 characters, every worst-case
 * adversarial term is 50 (the {@code @Size} ceiling). The derived budget therefore only clears the
 * adversarial terms because «іванівка» happens to carry ~3.8x their recheck count, and that is a
 * property of the DATA, not of the design. Simulated against the measured table: thinning the
 * {@code -івка} morphology by half drops the worst benign to 21 136 work units, the derived budget
 * to 42 272, while the worst ADMITTED term stays near 50 000 — the guard reddens with no defect
 * present. The exclusion set behind {@code settlement_hromadas.csv} is regenerated from Order 376
 * every few weeks and thinning that morphology is exactly what such a refresh does; a guard that
 * reddens for a non-defect is a guard somebody disables.
 *
 * <p>{@link #MIN_WORK_BUDGET} is the floor: the bound still TRACKS the data upwards (a bigger table
 * raises it) and stops tracking it downwards past the point where the measurement was taken. The
 * floor cannot silently rot into permissiveness, because
 * {@link #should_exceedTheBudget_when_aRefusedVectorIsMeasuredAnyway()} measures the refused vectors
 * against the SAME budget: if the data ever thinned far enough that 81 632 stopped discriminating,
 * that test goes red and says to re-derive the floor. Normalising both sides to
 * {@code MAX_QUERY_LENGTH} instead was considered and rejected — it throws away the length factor
 * that makes a 50-character {@code similarity()} evaluation genuinely more expensive than an
 * 8-character one, and it drops «•к»x25 (3 023 rechecks) below a normalised budget of 10 204, which
 * would turn the discrimination test red on a correct tree.
 *
 * <h3>The plan is a symptom; the index SIZE is the fact</h3>
 * Both plan-based tests here were falsified on 2026-09-23 against the very regression this class is
 * credited with catching. With V175's {@code restoreTrigramIndexHealth} removed — the unfixed tree —
 * the GIN stayed bloated at 5 488 kB and EVERY benign and admitted term still planned as an
 * IndexScan, with recheck counts byte-identical to the fixed tree, before AND after a
 * {@code VACUUM ANALYZE}. The plan is a reading of the planner's cost model against current
 * statistics, autovacuum timing and page cache; it reports this defect on some runs and not others,
 * which is also why the original regression first surfaced as "1 of 5 runs".
 * {@link #should_keepTheTrigramIndexCompact_when_theMigrationsHaveApplied()} asserts the artefact
 * instead — 1 204 224 B with both rebuilds, 2 023 424 B with V175's alone, 5 619 712 B with
 * neither — and {@code VACUUM} cannot shrink a GIN, only {@code REINDEX} can, so that reading is
 * autovacuum-immune. The plan assertions stay: they cover the index being dropped, renamed or made
 * unusable, which a size assertion cannot see.
 *
 * <p><b>The middle column is Phase 327's second finding.</b> V175's REINDEX runs inside V175's own
 * transaction, so the 26 041 dead tuples its UPDATE just created are not removable yet and the
 * rebuild inherits them. Ordinary traffic paid for that: «нов» read 706 buffers against 378 on the
 * reclaimed tree, «іванівка» 896 against 504 — an index scan throughout, so neither plan test above
 * could see it. {@code V176__vacuum_cities_after_hromada_backfill} is non-transactional and
 * {@code VACUUM}s before rebuilding; the ceiling below is what holds it in place.
 *
 * <p>Read-only throughout: every statement is an {@code EXPLAIN} or a {@code SELECT} against the
 * Flyway-seeded taxonomy, which {@link AbstractIntegrationTest#cleanDb()} deliberately does not
 * truncate.
 */
@DisplayName("Phase 326 settlement autocomplete — adversarial cost guard")
class SettlementSearchCostGuardIT extends AbstractIntegrationTest {

    /**
     * How much more work than the worst legitimate keystroke an anonymous caller may buy with one
     * request. The property is "no anonymous input costs materially more than a real user already
     * costs"; 2x is what "materially" means here, and the calibration in the class javadoc is what
     * makes it a bound rather than a guess.
     */
    private static final int MAX_WORK_RATIO = 2;

    /**
     * The trigram floor bound into the production finder ({@code SettlementSearchService
     * .MIN_SIMILARITY}), restated because that constant is package-private and this test lives in
     * the parent package. Widening it to public so a test can read it would export an
     * implementation detail on the strength of a test — the same call {@code SettlementSearchIT}
     * makes about {@code MAX_RESULTS}.
     */
    private static final double MIN_SIMILARITY = 0.3d;

    /**
     * The absolute floor under the derived budget, in work units: the budget measured on
     * 2026-09-23 against the real V170/V171/V175 taxonomy on postgres:16-alpine — worst benign
     * «іванівка» 5 102 rechecked x 8 characters = 40 816, x {@link #MAX_WORK_RATIO}. Below this the
     * bound stops being a statement about what the endpoint can be made to cost and becomes a
     * statement about how many {@code -івка} settlements survive the current exclusion refresh. See
     * the class javadoc for the simulation that produced it and for why normalising the two sides
     * to a common length was rejected instead.
     *
     * <p>If {@link #should_exceedTheBudget_when_aRefusedVectorIsMeasuredAnyway()} ever fails, THIS
     * number is what to re-derive — not that test's expectations.
     */
    private static final long MIN_WORK_BUDGET = 81_632L;

    /**
     * The GIN index the settlement autocomplete is served from — V173 creates it, V175's full-table
     * UPDATE bloats it, V175's {@code restoreTrigramIndexHealth} rebuilds it over its own
     * unremovable dead tuples, and {@code V176__vacuum_cities_after_hromada_backfill} rebuilds it
     * clean once those tuples are gone.
     */
    private static final String TRIGRAM_INDEX = "idx_cities_name_uk_trgm";

    /**
     * Ceiling on {@link #TRIGRAM_INDEX}'s on-disk size, in bytes. 1.5 MiB, placed between THREE
     * measured states of the same tree on 2026-09-23, so it discriminates both migrations that own
     * this index rather than only the first:
     *
     * <table>
     *   <caption>measured {@code pg_relation_size(idx_cities_name_uk_trgm)}</caption>
     *   <tr><td>V175 REINDEX + V176 VACUUM/REINDEX</td><td>1 204 224 B</td>
     *       <td>1.31x of headroom below the ceiling</td></tr>
     *   <tr><td>V175's REINDEX alone (V176 deleted)</td><td>2 023 424 B</td>
     *       <td>1.29x above — the rebuild inherits V175's own 26 041 dead tuples</td></tr>
     *   <tr><td>neither (V175's rebuild deleted too)</td><td>5 619 712 B</td>
     *       <td>3.57x above</td></tr>
     * </table>
     *
     * <p>It was 3 MiB, which cleared the middle row and therefore could not see V176 at all. Not a
     * round number chosen for tidiness — a discriminator with a measurement on each side of it, and
     * the ONLY assertion in the suite that fails if V176 is deleted or reordered before V175.
     *
     * <p>Autovacuum-immune by construction, which is why the size is asserted and the far more
     * obvious {@code n_dead_tup} is not: {@code VACUUM} reclaims GIN entries into the index's own
     * free space and cannot hand whole pages back to the filesystem, so this number never falls on
     * its own. A dead-tuple count, by contrast, is whatever the daemon last left behind.
     */
    private static final long MAX_TRIGRAM_INDEX_BYTES = 3L * 512 * 1024;

    private static final Pattern RECHECKED =
            Pattern.compile("\"Rows Removed by Index Recheck\": (\\d+)");

    private static final Pattern WHITESPACE =
            Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    private static final Pattern APOSTROPHES = Pattern.compile("['ʼ‘´]");

    @BeforeEach
    void freezeStatistics() {
        // The planner's choice between BitmapOr and Seq Scan depends on statistics, and a container
        // restored from a snapshot can carry none. Without this the whole class can pass for the
        // wrong reason on a cold container: no stats, a pessimistic plan, and a cost that no longer
        // reflects what production does.
        jdbcTemplate.execute("ANALYZE cities");
    }

    /**
     * One measurement of the production predicate, as the service would bind it.
     *
     * @param term      the normalised term, exactly as {@code SettlementSearchService} binds it
     * @param recheckedRows rows the GIN scan handed to the {@code similarity()} recheck
     * @param sequentialScan whether the planner abandoned {@code idx_cities_name_uk_trgm}
     */
    private record Cost(String term, long recheckedRows, boolean sequentialScan) {

        /** Rows rechecked x term length — see the class javadoc for why both factors are present. */
        long work() {
            return recheckedRows * term.length();
        }
    }

    /**
     * Mirrors {@code SettlementSearchService}'s normalisation — trim, collapse Unicode whitespace
     * runs, fold apostrophes onto the {@code U+2019} the column stores — because what costs the
     * database is the term as BOUND, not as typed. Duplicated rather than imported: the method is
     * private, and making it visible so a test can call it would let a future change to it silently
     * change what this class measures instead of failing here.
     */
    private static String normalize(String rawQuery) {
        String collapsed = WHITESPACE.matcher(rawQuery.trim()).replaceAll(" ");
        return APOSTROPHES.matcher(collapsed).replaceAll("’");
    }

    /** The LIKE-metacharacter escape the service applies before binding the prefix tier. */
    private static String likePrefixPattern(String term) {
        return term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private Cost measure(String rawTerm) {
        String term = normalize(rawTerm);
        // The two-tier OR is restated rather than imported, the same call
        // V173SettlementTrigramIndexMigrationTest's sibling assertion makes: what is load-bearing
        // for a COST measurement is the WHERE clause, and a copy of just that is honest about it.
        // The ORDER BY / LIMIT / JOIN add a sort over at most 20 surviving rows and cannot change
        // which rows the index hands to the recheck.
        String plan = jdbcTemplate.queryForObject("""
                EXPLAIN (ANALYZE, FORMAT JSON)
                SELECT c.id
                FROM cities c
                WHERE c.name_uk ILIKE ?
                   OR (c.name_uk % ? AND similarity(c.name_uk, ?) >= ?)
                """, String.class, likePrefixPattern(term), term, term, MIN_SIMILARITY);

        Matcher matcher = RECHECKED.matcher(plan == null ? "" : plan);
        long rechecked = matcher.find() ? Long.parseLong(matcher.group(1)) : 0L;
        return new Cost(term, rechecked, plan != null && plan.contains("Seq Scan"));
    }

    private long worstBenignWork() {
        return AdversarialSearchTerms.benignControls().stream()
                .map(this::measure)
                .mapToLong(Cost::work)
                .max()
                .orElseThrow();
    }

    /**
     * The bound every admitted term is held to: {@link #MAX_WORK_RATIO} x the worst benign query
     * measured in the SAME run, floored at {@link #MIN_WORK_BUDGET} so a data refresh that thins
     * the benign control cannot redden this class without a defect.
     */
    private long budget() {
        return Math.max(worstBenignWork() * MAX_WORK_RATIO, MIN_WORK_BUDGET);
    }

    // ── The harness must be able to see cost at all ───────────────────────────────────────────

    @Test
    @DisplayName("the measurement is not blind — a benign query reports non-zero rechecked rows")
    void should_reportRecheckedRows_when_measuringTheWorstBenignQuery() {
        // Without this, an EXPLAIN whose JSON shape changed (or a regex that stopped matching)
        // would report zero for EVERYTHING and every budget assertion in this class would pass
        // while measuring nothing at all. «іванівка» is the worst benign term by a wide margin —
        // 5 102 rechecked rows, 10.9 ms.
        //
        // NOT because of the 99-row Іванівка duplicate-name cluster, which an earlier revision of
        // this comment claimed: `Rows Removed by Index Recheck` counts the candidates that FAILED
        // the similarity() recheck, and the 99 that match are precisely the ones NOT counted.
        // Deleting every Іванівк% row leaves the figure at exactly 5 102. The cost is the `-івка`
        // trigram neighbourhood — 5 460 settlements on this table end in it — and the wrong story
        // is what made the budget look robust: a cluster is a stable landmark, a morphology is
        // thinned by every exclusion refresh. See MIN_WORK_BUDGET.
        Cost benign = measure("іванівка");

        assertThat(benign.recheckedRows())
                .as("EXPLAIN reported no 'Rows Removed by Index Recheck' for a term known to "
                        + "produce thousands — the measurement, not the endpoint, is broken")
                .isGreaterThan(1_000L);
        assertThat(benign.sequentialScan())
                .as("a benign 8-character term must be served from idx_cities_name_uk_trgm")
                .isFalse();
    }

    // ── The guard ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("every term the service ADMITS stays within 2x the worst legitimate query")
    void should_boundTheCostOfEveryAdmittedTerm_when_fedTheAdversarialCorpus() {
        long budget = budget();

        List<String> admitted = AdversarialSearchTerms.corpus().stream()
                .filter(term -> !SettlementSearchService.isNotIndexServable(term))
                .toList();

        assertThat(admitted)
                .as("if the admission predicate rejected the whole corpus this loop would measure "
                        + "nothing and pass — the ledger that keeps it honest")
                .hasSizeGreaterThan(10);

        List<Cost> costs = new ArrayList<>(admitted.stream().map(this::measure).toList());
        costs.sort(Comparator.comparingLong(Cost::work).reversed());

        List<Cost> overBudget = costs.stream().filter(cost -> cost.work() > budget).toList();

        assertThat(overBudget)
                .as("these terms pass every control the endpoint has — @Size(50), the @Pattern, the "
                        + "trigram-run guard — and still cost an anonymous caller more than twice "
                        + "the worst thing a real user can ask for. Budget %d work units from a "
                        + "worst benign of %d; the worst admitted term here is %s at %d. This is "
                        + "the same family as «ка »x17 (503 500) and «•к»x25 (151 100), and the "
                        + "fix belongs in the admission predicate, not in this budget",
                        budget, worstBenignWork(),
                        costs.isEmpty() ? "-" : costs.get(0).term(),
                        costs.isEmpty() ? 0 : costs.get(0).work())
                .isEmpty();
    }

    @Test
    @DisplayName("no admitted term makes the planner abandon the trigram index")
    void should_neverSequentiallyScan_when_theServiceAdmitsATerm() {
        // NOT a coarser restatement of the budget test — LOAD-BEARING, and the class javadoc's
        // "the metric is blind to a sequential scan" section says why: a Seq Scan plan reports no
        // "Rows Removed by Index Recheck", so measure() falls back to 0 and the budget test scores
        // the worst possible plan as free. «•»x50 measures 0 work units against a budget of 81 632
        // and would sail through the assertion above. This is the only test that sees it, as well
        // as the one that catches the index being dropped, renamed or made unusable by a migration
        // rather than an input walking past the guard. Do not fold the two together.
        //
        // BENIGN CONTROLS ARE IN THIS LOOP, and that is the Phase 327 correction. This test walked
        // corpus() alone, which encodes the assumption that the planner only ever gives up under
        // ATTACK. V175's full-table UPDATE falsified it: the bloated index made «нов» — three
        // characters, an ordinary keystroke, in no adversarial corpus — plan as a Seq Scan, and
        // this guard saw nothing, because «нов» is not an attack. The failure mode this class is
        // named for does not care how the term was typed, so neither does the loop: a planner that
        // abandons the index for NORMAL traffic is the same 25 698 similarity() evaluations per
        // request, reachable by anyone typing a city name rather than by someone crafting one.
        List<String> admitted = Stream.concat(
                        AdversarialSearchTerms.corpus().stream(),
                        AdversarialSearchTerms.benignControls().stream())
                .filter(term -> !SettlementSearchService.isNotIndexServable(term))
                .distinct()
                .toList();

        assertThat(admitted)
                .as("the ledger that keeps this loop honest — if the admission predicate ever "
                        + "refused everything, or a generator change emptied the corpus, this test "
                        + "would measure nothing and pass")
                .hasSizeGreaterThan(10)
                .containsAll(AdversarialSearchTerms.benignControls());

        List<String> scanned = admitted.stream()
                .map(this::measure)
                .filter(Cost::sequentialScan)
                .map(Cost::term)
                .toList();

        assertThat(scanned)
                .as("a term planned as a Seq Scan means 25 698 similarity() evaluations per "
                        + "request on a permitAll endpoint with a 240/60 s per-IP budget. If the "
                        + "names here are BENIGN the cause is not an input at all — it is the "
                        + "index, and the first suspect is a migration that rewrote `cities` "
                        + "without rebuilding idx_cities_name_uk_trgm (see V175's "
                        + "restoreTrigramIndexHealth)")
                .isEmpty();
    }

    // ── The artefact V175 must leave behind ───────────────────────────────────────────────────

    @Test
    @DisplayName("V175 left the trigram index rebuilt — the artefact, not the plan that follows it")
    void should_keepTheTrigramIndexCompact_when_theMigrationsHaveApplied() {
        // THE discriminator for the Phase 327 regression, and the reason the two plan tests above
        // are no longer the only thing between this endpoint and a full-table sweep.
        //
        // Falsified 2026-09-23 by deleting `restoreTrigramIndexHealth(connection)` from V175 and
        // re-running the whole chain: every benign control and all 92 admitted adversarial terms
        // still planned as an IndexScan with recheck counts identical to the fixed tree, before AND
        // after `VACUUM ANALYZE` — both plan tests GREEN over a live regression. The index measured
        // 5 619 712 B against 2 023 424 B rebuilt, and only this assertion went red.
        //
        // The ceiling was then TIGHTENED from 3 MiB to 1.5 MiB, because 3 MiB could not see the
        // second half of the defect: V175's in-transaction rebuild inherits its own 26 041 dead
        // tuples and lands at 2 023 424 B, comfortably under the old ceiling, costing ordinary
        // autocomplete terms ~+95 % buffer reads until autovacuum fires. Deleting V176 now reddens
        // this assertion and nothing else in the suite — measured, not assumed.
        //
        // It is autovacuum-immune by construction: VACUUM reclaims GIN entries into the index's own
        // free space and cannot hand whole pages back to the filesystem, so pg_relation_size does
        // not fall no matter when autovacuum last ran. Only REINDEX moves this number down, which
        // is exactly the statement V175 must have issued.
        Long indexBytes = jdbcTemplate.queryForObject(
                "SELECT pg_relation_size(?::regclass)", Long.class, TRIGRAM_INDEX);

        assertThat(indexBytes)
                .as("%s is missing or empty — pg_relation_size returned %s. The settlement "
                        + "autocomplete then has no index at all, which is worse than the bloat "
                        + "this test exists to catch", TRIGRAM_INDEX, indexBytes)
                .isNotNull()
                .isGreaterThan(0L);
        assertThat(indexBytes)
                .as("%s is %d B, over the %d B ceiling. A full-table UPDATE on `cities` bloated it "
                        + "and nothing rebuilt it: the planner prices a GIN this size out of "
                        + "ORDINARY queries on a permitAll endpoint with a 240/60 s per-IP budget, "
                        + "which is the Phase 326 denial-of-service vector re-opened by a data "
                        + "migration. VACUUM cannot fix it — the migration that rewrote the table "
                        + "owes a REINDEX INDEX %s, the way V175's restoreTrigramIndexHealth does. "
                        + "Do NOT relax this number to make a migration green",
                        TRIGRAM_INDEX, indexBytes, MAX_TRIGRAM_INDEX_BYTES, TRIGRAM_INDEX)
                .isLessThan(MAX_TRIGRAM_INDEX_BYTES);
    }

    @Test
    @DisplayName("the floor alone clears every admitted term — a thinned corpus cannot redden this")
    void should_clearEveryAdmittedTerm_when_onlyTheFloorIsApplied() {
        // The reason MIN_WORK_BUDGET exists, asserted rather than asserted-about-in-a-comment.
        // budget() is max(2 x worst benign, MIN_WORK_BUDGET); this pins the SECOND term on its own,
        // which is the value the class falls back to when a data refresh thins the benign control.
        // If it did not clear the worst admitted term, the floor would be decorative and this class
        // would still go red the first time the -івка morphology shrinks — with no defect present,
        // which is how a guard gets disabled.
        //
        // The other side of the floor is should_exceedTheBudget_when_aRefusedVectorIsMeasuredAnyway:
        // together they say MIN_WORK_BUDGET sits strictly between the worst ADMITTED term and the
        // cheapest REFUSED one. Both are measurements of this table, so a real data change moves
        // them and one of the two says so.
        long worstAdmitted = AdversarialSearchTerms.corpus().stream()
                .filter(term -> !SettlementSearchService.isNotIndexServable(term))
                .map(this::measure)
                .mapToLong(Cost::work)
                .max()
                .orElseThrow();

        assertThat(worstAdmitted)
                .as("the worst term the service ADMITS costs %d work units against a floor of %d. "
                        + "Either an input class got cheaper to admit — in which case re-derive the "
                        + "floor downwards — or the corpus found something new, in which case the "
                        + "fix belongs in SettlementSearchService's admission predicate",
                        worstAdmitted, MIN_WORK_BUDGET)
                .isLessThan(MIN_WORK_BUDGET);
    }

    // ── The budget has to discriminate, or the guard above is decorative ──────────────────────

    @Test
    @DisplayName("the refused vectors WOULD blow the budget — the Java guard is load-bearing")
    void should_exceedTheBudget_when_aRefusedVectorIsMeasuredAnyway() {
        // The falsification, kept in the suite. It measures what the two closed vectors cost IF the
        // admission predicate ever lets them through again, which is what makes the budget above a
        // discriminator rather than a number that happens to be larger than everything tested.
        //
        // Deliberately NOT the same shape as
        // SettlementSearchIT.should_sequentialScanTheWholeTable_when_aZeroTrigramTermIsPlanned,
        // which pins a PostgreSQL property and cannot fail by design. Every number here is a
        // measurement of THIS table through THIS index, so a data or index change moves it and this
        // test says so.
        long budget = budget();

        Cost paddedBigrams = measure("ка ".repeat(16) + "ка");
        Cost interleaved = measure("•к".repeat(25));

        assertThat(SettlementSearchService.isNotIndexServable("ка ".repeat(16) + "ка"))
                .as("the service must refuse it BEFORE the database ever sees it")
                .isTrue();
        assertThat(SettlementSearchService.isNotIndexServable("•к".repeat(25))).isTrue();

        assertThat(paddedBigrams.work())
                .as("«ка »x17 cost 503 500 work units / 51.7 ms when this was written — if it has "
                        + "fallen under the budget the endpoint's exposure changed and the "
                        + "reasoning in SettlementSearchService needs revisiting, not this number")
                .isGreaterThan(budget);
        assertThat(interleaved.work())
                .as("«•к»x25 cost 151 100 work units / 20.4 ms — the vector the generated corpus "
                        + "found and no human tried")
                .isGreaterThan(budget);
    }

    @Test
    @DisplayName("the zero-trigram vector still plans as a full scan — nothing in SQL stops it")
    void should_stillSequentiallyScan_when_theZeroTrigramVectorIsMeasured() {
        // Kept because it is the reason the guard cannot live in the database: there is no index,
        // threshold or statement timeout that makes this input cheap. Unlike the bare EXPLAIN in
        // SettlementSearchIT, this asserts BOTH halves at once — the plan AND that the service
        // refuses the input — so it cannot drift into documenting a property of a term the
        // endpoint no longer accepts.
        Cost zeroTrigram = measure("•".repeat(50));

        assertThat(zeroTrigram.sequentialScan()).isTrue();
        assertThat(SettlementSearchService.isNotIndexServable("•".repeat(50))).isTrue();
    }
}
