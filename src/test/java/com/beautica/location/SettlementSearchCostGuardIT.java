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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>COST GUARD — what every term the service ADMITS actually costs the database.</b>
 *
 * <p>The audit this class comes out of had one finding above all the others: two unauthenticated
 * denial-of-service vectors shipped on {@code GET /api/v1/settlements} through a suite that was
 * green, and one of them was fed to an existing test verbatim
 * ({@code SettlementSearchIT.should_treatUnderscoreAsLiteral_when_queryContainsALikeWildcard})
 * which passed, because it asserted SEMANTICS — the result was empty — and an empty result is
 * equally consistent with "escaped correctly" and with "scanned 25 697 rows and matched nothing".
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
 * 25 697-row table at the time of writing: worst benign «іванівка» 40 816 work units / 10.9 ms;
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
 * {@code "•".repeat(50)} — 25 697 {@code similarity()} evaluations, 119 ms, the vector the whole
 * phase exists to close — measures 0. So
 * {@link #should_boundTheCostOfEveryAdmittedTerm_when_fedTheAdversarialCorpus()} would PASS an
 * admitted term that scans the entire table, and
 * {@link #should_neverSequentiallyScan_when_theServiceAdmitsATerm()} is what actually covers that
 * case. The two look like coarse and fine versions of one property and are not: neither subsumes
 * the other, the cheap-looking half of the budget test is precisely the expensive half of reality,
 * and deleting the Seq Scan test as a duplicate would remove the only assertion standing between
 * this endpoint and the plan it was written to prevent.
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

    // ── The harness must be able to see cost at all ───────────────────────────────────────────

    @Test
    @DisplayName("the measurement is not blind — a benign query reports non-zero rechecked rows")
    void should_reportRecheckedRows_when_measuringTheWorstBenignQuery() {
        // Without this, an EXPLAIN whose JSON shape changed (or a regex that stopped matching)
        // would report zero for EVERYTHING and every budget assertion in this class would pass
        // while measuring nothing at all. «іванівка» is the 99-row duplicate-name cluster and is
        // the worst benign term by a wide margin — 5 102 rechecked rows, 10.9 ms.
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
        long budget = worstBenignWork() * MAX_WORK_RATIO;

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
                        budget, budget / MAX_WORK_RATIO,
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
        List<String> scanned = AdversarialSearchTerms.corpus().stream()
                .filter(term -> !SettlementSearchService.isNotIndexServable(term))
                .map(this::measure)
                .filter(Cost::sequentialScan)
                .map(Cost::term)
                .toList();

        assertThat(scanned)
                .as("an admitted term planned as a Seq Scan means 25 697 similarity() evaluations "
                        + "per request on a permitAll endpoint with a 240/60 s per-IP budget")
                .isEmpty();
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
        long budget = worstBenignWork() * MAX_WORK_RATIO;

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
