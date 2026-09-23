package com.beautica.location.service;

import com.beautica.location.repository.CityRepository;
import com.beautica.location.repository.CityRepository.SettlementSearchRow;
import com.beautica.search.service.NormalizedSearchQuery;
import com.beautica.support.AdversarialSearchTerms;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>ADMISSION GUARD — the class-level regression net for the denial-of-service vectors that
 * shipped through a green suite on {@code GET /api/v1/settlements}.</b>
 *
 * <p>This class does not add a case for the strings that were found by hand. It asserts the
 * PROPERTY all of them violated, over a generated corpus of the same shape
 * ({@link AdversarialSearchTerms}), so the next member of the family is already covered on the day
 * it is invented.
 *
 * <h3>The property</h3>
 * An anonymous caller must not be able to make this endpoint issue a statement the GIN index
 * cannot serve SELECTIVELY. Selectivity is a function of how many DISTINCT trigram keys the term
 * yields, and pg_trgm derives those from uninterrupted alphanumeric RUNS — so the admission
 * decision has to be about the longest run, and every proxy for it (the term's length; the term's
 * length plus "an alphanumeric appears somewhere"; a token's length) has now been defeated in
 * production by an input that padded its way past it.
 *
 * <h3>Why the assertion is {@code verify(never())} and not an empty return value</h3>
 * An empty list is equally consistent with "the guard refused it" and with "the database scanned
 * 25 697 rows and matched nothing" — that ambiguity is exactly how the first vector shipped, past a
 * test ({@code SettlementSearchIT}'s LIKE-wildcard case) that fed it the offending input and
 * asserted only semantics. The observable that tells the two apart is whether the repository was
 * called at all.
 *
 * <h3>What this class deliberately does NOT prove</h3>
 * That an admitted term is actually cheap. A mocked repository cannot know that; it is
 * {@code SettlementSearchCostGuardIT} that takes every term this class reports as admitted and
 * measures what it costs against the real 25 697-row table. The two are a pair — this one pins the
 * decision, that one pins the consequence — and neither subsumes the other.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SettlementSearchService — adversarial admission guard (Phase 326 DoS regression net)")
class SettlementSearchAdmissionGuardTest {

    /**
     * The vector {@code backend-perf} found and this audit closed: 50 characters, seventeen
     * 2-character tokens, 10 070 {@code similarity()} rechecks, 47.5 ms.
     */
    private static final String PADDED_BIGRAM_VECTOR = "ка ".repeat(16) + "ка";

    /**
     * The vector the CORPUS found, which no human had tried: 50 characters, ONE token (so a
     * per-token floor admits it), alphanumeric-bearing (so the trigram guard admits it), and yet
     * only two distinct keys because every alphanumeric run is a single letter — 3 022 rechecks,
     * 21.7 ms.
     */
    private static final String PUNCTUATION_INTERLEAVED_VECTOR = "•к".repeat(25);

    /** The vector {@code backend-security} closed: no alphanumeric at all, Seq Scan, 92.5 ms. */
    private static final String ZERO_TRIGRAM_VECTOR = "•".repeat(50);

    /**
     * The property, restated from the pg_trgm side rather than imported from production.
     *
     * <p>This is deliberately a SECOND expression of the rule, not a reuse of
     * {@link NormalizedSearchQuery#hasIndexServableRun(String)}: a test that calls the predicate
     * under test to decide what the predicate under test should have done cannot fail. What it
     * encodes is the database fact — a run of fewer than three alphanumerics contributes only
     * padded boundary keys, which thousands of settlement names share.
     */
    private static final Pattern ALPHANUMERIC_RUN =
            Pattern.compile("[\\p{IsAlphabetic}\\p{IsDigit}]+");

    @Mock
    private CityRepository cityRepository;

    private SettlementSearchService service;

    @BeforeEach
    void setUp() {
        service = new SettlementSearchService(cityRepository);
        ReflectionTestUtils.setField(service, "self", service);
        // A NON-EMPTY row, so "the service returned nothing" means "the service refused" and not
        // "the stub had nothing to give". With an empty stub every assertion below about the
        // returned list would hold for both outcomes.
        when(cityRepository.searchByName(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn(List.of(row()));
    }

    private static SettlementSearchRow row() {
        UUID id = UUID.randomUUID();
        return new SettlementSearchRow() {
            @Override public UUID getSettlementId() {
                return id;
            }

            @Override public String getNameUk() {
                return "Львів";
            }

            @Override public String getSettlementType() {
                return "CITY";
            }

            @Override public String getOblastNameUk() {
                return "Львівська";
            }
        };
    }

    static List<String> corpus() {
        return AdversarialSearchTerms.corpus();
    }

    // ── The named vectors ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("«ка »x17 — 50 chars of 2-character tokens — never reaches the database")
    void should_refuseWithoutQuerying_when_termIsPaddedWithSubTrigramTokens() {
        // Clears @Size (exactly 50 characters), clears the 3-character floor (the TERM is 50
        // characters long) and clears the zero-trigram guard (it is entirely alphanumeric and
        // whitespace). Three controls satisfied, and the query still cost 14.8x the documented
        // worst case, because the whole term yields {"  к", " ка", "ка "} — three distinct keys, of
        // which one match is enough at a 0.3 threshold.
        assertThat(service.search(PADDED_BIGRAM_VECTOR))
                .as("an unservable term returns an EXPLICIT empty list, never the major list")
                .isEmpty();

        verify(cityRepository, never()).searchByName(anyString(), anyString(), anyDouble(), anyInt());
    }

    @Test
    @DisplayName("«•к»x25 — one 50-char token, two distinct keys — never reaches the database")
    void should_refuseWithoutQuerying_when_everyAlphanumericRunIsASingleCharacter() {
        // The case for generating the corpus rather than extending the case list: nobody tried this
        // string. It defeats a per-TOKEN floor (one token, 50 characters) the way «ка »x17 defeats
        // a per-TERM one, and it was 21.7 ms on the real table — twice the worst benign query.
        assertThat(service.search(PUNCTUATION_INTERLEAVED_VECTOR)).isEmpty();

        verify(cityRepository, never()).searchByName(anyString(), anyString(), anyDouble(), anyInt());
    }

    @Test
    @DisplayName("«•»x50 — the closed zero-trigram vector — stays closed")
    void should_refuseWithoutQuerying_when_termBearsNoAlphanumericAtAll() {
        assertThat(service.search(ZERO_TRIGRAM_VECTOR)).isEmpty();

        verify(cityRepository, never()).searchByName(anyString(), anyString(), anyDouble(), anyInt());
    }

    // ── The property, over the generated corpus ───────────────────────────────────────────────

    @ParameterizedTest(name = "[{index}] admitted only with a 3-char run: {0}")
    @MethodSource("corpus")
    @DisplayName("no term is executed unless it carries a full alphanumeric trigram run")
    void should_admitOnlyTermsCarryingATrigramRun_when_fedTheAdversarialCorpus(String term) {
        List<?> results = service.search(term);
        boolean executed = !results.isEmpty();

        verify(cityRepository, times(executed ? 1 : 0))
                .searchByName(anyString(), anyString(), anyDouble(), anyInt());

        assertThat(executed)
                .as("term %s: it was executed against a permitAll endpoint, but its longest "
                        + "alphanumeric run is %d characters. pg_trgm pads each run, so a run "
                        + "shorter than %d contributes boundary keys only — keys that thousands of "
                        + "settlement names share — and the scan degenerates into a similarity() "
                        + "recheck over most of the table",
                        term, longestAlphanumericRun(term), NormalizedSearchQuery.MIN_QUERY_LENGTH)
                .isEqualTo(longestAlphanumericRun(term) >= NormalizedSearchQuery.MIN_QUERY_LENGTH);
    }

    @Test
    @DisplayName("the user-facing hint and the refusal are ONE decision, not two that agree")
    void should_hintExactlyWhenItRefuses_when_classifyingEveryCorpusTerm() {
        // The controller attaches «Введіть щонайменше 3 символи» from isNotIndexServable, and
        // search() decides independently whether to issue a statement. While those were two
        // predicates they disagreed in production: a 50-character punctuation run returned an
        // empty list with NO hint, because the hint was length-only. This is the assertion that
        // keeps them the same decision.
        //
        // Aggregated rather than parameterised: the corpus is 400+ terms and the admission property
        // above already reports per term, so a second parameterised sweep would double a CI report
        // no one can read for no extra diagnostic — the mismatch list names the offenders anyway.
        List<String> disagreements = new ArrayList<>();
        for (String term : AdversarialSearchTerms.corpus()) {
            boolean executed = !service.search(term).isEmpty();
            if (SettlementSearchService.isNotIndexServable(term) != !executed) {
                disagreements.add(term);
            }
        }

        assertThat(disagreements)
                .as("for these terms the endpoint returned nothing without telling the user why, "
                        + "or hinted at a term it executed anyway")
                .isEmpty();
    }

    /** Longest uninterrupted alphanumeric run in {@code term}; 0 when it has none. */
    private static int longestAlphanumericRun(String term) {
        int longest = 0;
        Matcher matcher = ALPHANUMERIC_RUN.matcher(term);
        while (matcher.find()) {
            longest = Math.max(longest, matcher.group().length());
        }
        return longest;
    }

    // ── Differential against the sibling public surface ───────────────────────────────────────

    @Test
    @DisplayName("this endpoint is at least as strict as the sibling /search/** normaliser")
    void should_refuseEverythingTheSiblingSearchRefuses_when_bothSeeTheSameTerm() {
        // NormalizedSearchQuery was hardened first, independently, and was ALREADY immune to
        // «ка »x17 because it tests tokens where this endpoint tested the concatenation. That
        // asymmetry IS the defect, and this assertion makes the relationship structural rather than
        // a fix to one string: whatever the shared normaliser refuses, the settlement autocomplete
        // refuses too — now, and after any future tightening of either side.
        //
        // The one documented divergence is the sibling's MAX_TOKENS cap: of() keeps only the first
        // four tokens, so a term whose only servable token is the fifth is refused there and served
        // here. That is deliberate and measured safe (a real 5-letter token raises the distinct-key
        // count, which RAISES the threshold's minimum-match count and makes the scan more
        // selective: «ка ка ка ка Львів» is 350 rechecks / 1.8 ms). Those terms are excluded BY THAT
        // RULE, not by name, so a new corpus entry of that shape needs no edit here.
        List<String> leaks = new ArrayList<>();
        for (String term : AdversarialSearchTerms.corpus()) {
            if (!NormalizedSearchQuery.of(term).belowMinimumLength()) {
                continue;
            }
            if (hasServableTokenBeyondTheSiblingCap(term)) {
                continue;
            }
            if (!SettlementSearchService.isNotIndexServable(term)) {
                leaks.add(term);
            }
        }

        assertThat(leaks)
                .as("these terms are refused by GET /search/** and admitted by GET /settlements. "
                        + "Two public unauthenticated surfaces over the same pg_trgm index "
                        + "disagreeing about what is servable is precisely how the 47 ms vector "
                        + "shipped")
                .isEmpty();
    }

    private static boolean hasServableTokenBeyondTheSiblingCap(String term) {
        String[] tokens = term.trim().split("\\s+");
        for (int i = NormalizedSearchQuery.MAX_TOKENS; i < tokens.length; i++) {
            if (NormalizedSearchQuery.isServableToken(tokens[i])) {
                return true;
            }
        }
        return false;
    }

    // ── The corpus must not be able to pass vacuously ─────────────────────────────────────────

    @Test
    @DisplayName("the corpus is large, mostly hostile, and still contains admitted terms")
    void should_coverBothOutcomes_when_theCorpusIsGenerated() {
        // A loop over an empty or single-outcome corpus passes for the wrong reason. This is the
        // ledger under both @MethodSource tests above: a generator that silently stops producing
        // separators, or produces only refusable terms, fails HERE instead of turning two
        // parameterised tests into green no-ops and the cost IT into a loop over nothing.
        List<String> terms = AdversarialSearchTerms.corpus();
        long refused = terms.stream().filter(SettlementSearchService::isNotIndexServable).count();

        assertThat(terms).hasSizeGreaterThan(100);
        assertThat(refused)
                .as("the corpus exists to exercise the refusal path")
                .isGreaterThan(40);
        assertThat(terms.size() - refused)
                .as("and the admission path, or SettlementSearchCostGuardIT measures nothing")
                .isGreaterThan(10);
    }

    @Test
    @DisplayName("every benign control is still served — the guard did not tighten onto real input")
    void should_stillAdmitRealSettlementQueries_when_theGuardIsApplied() {
        // The direction nobody notices until users do. «іван фран» is a phase-326 acceptance
        // criterion, «кам'янка» exercises the apostrophe fold, and a guard "tightened" into
        // requiring every run to reach the floor would reject «с. Нове» and «Івано-Франківськ»
        // while every assertion above stayed green. Exactly two of the 25 697 settlements have no
        // 3-character run at all — the two villages named «Яр» — and both were already unreachable
        // under the pre-existing MIN_QUERY_LENGTH floor, so this guard costs no real caller a row.
        for (String benign : AdversarialSearchTerms.benignControls()) {
            assertThat(SettlementSearchService.isNotIndexServable(benign))
                    .as("benign term %s must reach the database", benign)
                    .isFalse();
        }
        assertThat(SettlementSearchService.isNotIndexServable("с. Нове")).isFalse();
        assertThat(SettlementSearchService.isNotIndexServable("Івано-Франківськ")).isFalse();
        assertThat(SettlementSearchService.isNotIndexServable("112")).isFalse();
    }
}
