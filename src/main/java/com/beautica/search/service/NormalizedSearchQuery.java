package com.beautica.search.service;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Normalised form of the public search free-text parameter {@code q} — the
 * single source of truth for how a caller's raw query string becomes SQL
 * predicates on {@code /search/masters} and {@code /search/salons}.
 *
 * <h3>Why this exists (defect B)</h3>
 * The former {@code SearchService.normalizeQuery} returned {@code null} for
 * <em>two</em> semantically opposite inputs: "no query supplied" and "query
 * supplied but too short to run". Both dropped the predicate, so {@code ?q=Ру}
 * returned the <b>entire</b> unfiltered result set — a dropped filter
 * masquerading as "no filter". This record keeps the two states apart:
 *
 * <ul>
 *   <li>{@link #isAbsent()} — nothing was typed; no {@code q} predicate is
 *       emitted and the caller sees the location-scoped result set. This is
 *       correct and unchanged.</li>
 *   <li>{@link #belowMinimumLength()} — something was typed but no token
 *       reaches {@link #MIN_QUERY_LENGTH}. The caller must return an
 *       <b>explicit empty page</b> plus a helper message; it must never fall
 *       back to the unfiltered set. Not a 400 — a 1-char query is a legitimate
 *       keystroke in an incremental search box, not malformed input.</li>
 *   <li>{@link #hasTokens()} — the executable case; {@link #tokens()} carries
 *       1..{@link #MAX_TOKENS} terms.</li>
 * </ul>
 *
 * <h3>Why a 3-character floor at all</h3>
 * A {@code pg_trgm} GIN index needs one full 3-gram to serve a containment
 * {@code ILIKE}; a 1–2 char term matches no trigram and degrades into a
 * full-table sequential scan on every public, unauthenticated request. The
 * floor is kept — only its <em>honesty</em> changed. It is applied to the
 * <b>longest retained token</b>, not to every token: once one token is
 * trigram-servable it drives the scan and the shorter tokens are cheap
 * ANDed rechecks, so "Мар'я Ко" still filters on both terms instead of
 * silently discarding one.
 *
 * <h3>Tokenisation (defect C)</h3>
 * The whole term used to become one {@code %…%} pattern tested against each
 * column separately, so {@code first_name ILIKE '%Вікторія Руденко%'} was false
 * for "Вікторія" and {@code last_name ILIKE '%Вікторія Руденко%'} was false for
 * "Руденко" — a full name could never match. Splitting on whitespace and
 * <b>AND</b>-ing the tokens (each token OR-ed across the searchable columns)
 * fixes that and makes word order irrelevant ("стрижка жіноча" ≡ "жіноча
 * стрижка"). The token count is capped at {@link #MAX_TOKENS} so a 100-char
 * query cannot expand into an unbounded predicate; the first {@code MAX_TOKENS}
 * tokens are retained.
 *
 * <h3>Apostrophe folding (defect A)</h3>
 * Ukrainian names carry an apostrophe (В'ячеслав, Мар'яна, Юр'ївна) that
 * keyboards emit as either U+0027 or the curly U+2019 / U+02BC / U+2018. Stored
 * names use the straight U+0027, so every curly variant is folded onto it and
 * both keyboard forms match the same rows. This is deliberately a
 * <em>query-side</em> fold: folding the stored column would need an expression
 * index, and the data is already normalised on the write side.
 *
 * @param tokens              the executable search terms, already
 *                            apostrophe-folded and trimmed; empty when the
 *                            query is absent or below the minimum length. The
 *                            {@code LIKE}-wildcard escaping is applied later
 *                            (see {@code SearchService.likeContains}) so the
 *                            escaping stays in one place.
 * @param belowMinimumLength  {@code true} iff a non-blank query was supplied
 *                            but no retained token reaches
 *                            {@link #MIN_QUERY_LENGTH}
 */
public record NormalizedSearchQuery(List<String> tokens, boolean belowMinimumLength) {

    /**
     * Minimum trigram-servable token length. At least one retained token must
     * reach it, otherwise the query is reported as
     * {@link #belowMinimumLength()}.
     *
     * <p><b>Contract note:</b> the user-facing helper text in
     * {@code SearchController} hard-codes "3" (Ukrainian numerals inflect, so
     * the string cannot be interpolated safely). Changing this constant means
     * changing that message too.</p>
     */
    public static final int MIN_QUERY_LENGTH = 3;

    /**
     * Maximum number of whitespace tokens carried into the SQL predicate.
     *
     * <p><b>Must stay equal to</b>
     * {@code SalonSearchSql.STATIC_TOKEN_PARAM_COUNT} — the static salon
     * projection queries declare exactly that many {@code :qN} bind parameters,
     * and {@code SearchService} pads the token list out to it.</p>
     */
    public static final int MAX_TOKENS = 4;

    /** Absent query — no {@code q} predicate at all. */
    private static final NormalizedSearchQuery ABSENT = new NormalizedSearchQuery(List.of(), false);

    /** Supplied but not trigram-servable — an explicit empty page, never the unfiltered set. */
    private static final NormalizedSearchQuery BELOW_MINIMUM = new NormalizedSearchQuery(List.of(), true);

    /** Unicode-aware so a non-breaking space separates tokens like a plain space does. */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    /**
     * Apostrophe variants folded onto U+0027: U+2019 RIGHT SINGLE QUOTATION
     * MARK (the iOS/Android default), U+02BC MODIFIER LETTER APOSTROPHE (the
     * typographically "correct" Ukrainian form), U+2018 LEFT SINGLE QUOTATION
     * MARK, U+00B4 ACUTE ACCENT.
     */
    private static final Pattern CURLY_APOSTROPHES = Pattern.compile("[’ʼ‘´]");

    private static final String STRAIGHT_APOSTROPHE = "'";

    /**
     * One character that {@code pg_trgm} will actually extract a trigram from.
     *
     * <p><b>This is the predicate {@link #MIN_QUERY_LENGTH} was always meant to express and does
     * not.</b> A length test is a PROXY for "the GIN index can serve this"; it is not the thing
     * itself. {@code pg_trgm} tokenises on alphanumeric runs and treats every other character as a
     * separator, so {@code show_trgm('•••')} is {@code {}} — an EMPTY key set. A query with an
     * empty key set cannot be answered from a GIN index at all: the planner abandons
     * {@code idx_cities_name_uk_trgm} / {@code idx_*_name_trgm} and falls back to a sequential
     * scan that evaluates {@code similarity()} on every row.
     *
     * <p>Measured on the real 25 698-row {@code cities} table: {@code 'нов'} = 3.4 ms via
     * BitmapOr; {@code "•".repeat(100)} = 119 ms, {@code Seq Scan}, {@code Rows Removed by Filter:
     * 25697}. {@code pgbench -c 10 -t 24} over one IP's full 240-token minute saturated the whole
     * Hikari pool for 6.02 s, on {@code permitAll} endpoints, unauthenticated — so the length
     * floor alone was a denial-of-service surface on both {@code /search/**} and
     * {@code /settlements}. ONE alphanumeric anywhere in the term restores the index scan
     * ({@code "•".repeat(49) + "о"} re-measured at 3.5 ms, against 3.2 ms for the benign «нов»),
     * which is why this asks for PRESENCE and not for a ratio or an all-characters test: one letter
     * is genuinely enough to make the query cheap, and a stricter rule would start rejecting real
     * input — «Кам’янка», «Івано-Франківськ», «с. Нове» — for no measurable benefit.
     *
     * <p>Alphabetic is deliberately the Unicode property, not {@code [a-z]}: Cyrillic is the
     * primary alphabet here and {@code \p{Alnum}} is ASCII-only.
     */
    private static final Pattern TRIGRAM_BEARING =
            Pattern.compile("[\\p{IsAlphabetic}\\p{IsDigit}]");

    /**
     * An alphanumeric run long enough for pg_trgm to extract one INTERIOR 3-gram from it — the
     * predicate {@link #TRIGRAM_BEARING} is the weakened, defeatable form of. See
     * {@link #hasIndexServableRun(String)} for the three measured inputs that walked through the
     * weaker spellings of this check.
     */
    private static final Pattern TRIGRAM_RUN =
            Pattern.compile("[\\p{IsAlphabetic}\\p{IsDigit}]{" + MIN_QUERY_LENGTH + ",}");

    /** Defensive copy — the token list is part of a value object and must be immutable. */
    public NormalizedSearchQuery {
        tokens = List.copyOf(tokens);
    }

    /**
     * Normalises a raw {@code q} query parameter. Never returns {@code null};
     * the three outcomes are distinguished by {@link #isAbsent()},
     * {@link #belowMinimumLength()} and {@link #hasTokens()}.
     */
    public static NormalizedSearchQuery of(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return ABSENT;
        }
        String folded = foldApostrophes(rawQuery);
        List<String> retained = WHITESPACE.splitAsStream(folded.trim())
                .filter(token -> !token.isEmpty())
                .limit(MAX_TOKENS)
                .toList();
        if (retained.isEmpty()) {
            // Only reachable for input that is entirely Unicode whitespace not
            // covered by String#isBlank — treat it as "nothing was typed".
            return ABSENT;
        }
        // Trigram-servable means BOTH long enough AND trigram-bearing. The length half alone let
        // `?q=•••` through to SearchService#likeContains, where it became a `%•••%` predicate with
        // an empty GIN key set and a full sequential scan — see TRIGRAM_BEARING for the numbers.
        // A token that fails only the second half is not "a short query", it is an unservable one,
        // and BELOW_MINIMUM is already the state for "typed, but cannot be run": an explicit empty
        // page plus the hint, never the unfiltered set and never a scan.
        boolean trigramServable = retained.stream().anyMatch(NormalizedSearchQuery::isServableToken);
        return trigramServable ? new NormalizedSearchQuery(retained, false) : BELOW_MINIMUM;
    }

    /**
     * Folds the four curly apostrophe variants onto {@code U+0027} — the single
     * definition of the fold {@link #of(String)} applies to the query side.
     *
     * <p>Exposed because the {@code q}-token → {@code platform_categories.display_name}
     * match ({@code QueryCategoryMatcher}) runs in Java rather than in SQL, so it has
     * to fold <b>both</b> operands the same way this class folds the query. Stored
     * display names use the straight {@code U+0027} today
     * («Ін'єкційна косметологія»), but relying on that is exactly the assumption that
     * breaks the day an admin pastes a name from a word processor: folding both sides
     * through one function makes the match insensitive to which apostrophe either side
     * happens to carry. Re-implementing the fold at the call site would be a second
     * definition free to drift from the one {@code ILIKE} patterns are built with.
     *
     * @param value raw text; must not be {@code null}
     * @return {@code value} with every curly apostrophe variant replaced
     */
    public static String foldApostrophes(String value) {
        return CURLY_APOSTROPHES.matcher(value).replaceAll(STRAIGHT_APOSTROPHE);
    }

    /**
     * Whether {@code pg_trgm} can extract at least one trigram from {@code term} — i.e. whether a
     * GIN {@code gin_trgm_ops} index can serve a predicate built from it at all.
     *
     * <p>Exposed for the same reason {@link #foldApostrophes(String)} is: a SECOND surface needs
     * the identical answer. {@code SettlementSearchService} does not route through
     * {@link #of(String)} — it has its own three-state routing over a different table and a
     * different apostrophe fold — but it guards the same GIN index against the same empty-key-set
     * scan. Two copies of a regex that decides whether an unauthenticated request can saturate the
     * connection pool is exactly the drift this project has shipped bugs from; one definition, two
     * readers.
     *
     * @param term a single already-trimmed term or token; must not be {@code null}
     * @return {@code true} when the term contains at least one alphanumeric character
     */
    public static boolean bearsTrigrams(String term) {
        return TRIGRAM_BEARING.matcher(term).find();
    }

    /**
     * Whether ONE whitespace-delimited token can drive a {@code gin_trgm_ops} index scan on its own
     * — long enough to yield a full 3-gram AND carrying an alphanumeric run for pg_trgm to tokenise.
     *
     * <p>Both halves, together, in one place. {@link #of(String)} applied them as an inline
     * conjunction and {@code SettlementSearchService} applied them as two separate statements over
     * the WHOLE term rather than per token — the drift that shipped defect 2 (see
     * {@link #hasIndexServableRun(String)}).
     *
     * @param token a single whitespace-free term; must not be {@code null}
     * @return {@code true} when a GIN trigram index can serve a predicate built from this token
     */
    public static boolean isServableToken(String token) {
        return token.length() >= MIN_QUERY_LENGTH && bearsTrigrams(token);
    }

    /**
     * Whether a raw term contains at least one uninterrupted alphanumeric RUN of
     * {@link #MIN_QUERY_LENGTH} characters — i.e. whether pg_trgm can extract a single interior
     * 3-gram from it, rather than only the padded boundary keys of short fragments.
     *
     * <h4>Why the run, and not the term's length, and not a token's length</h4>
     * Three successively weaker proxies each admitted the input the next one had to catch, on the
     * same {@code permitAll} endpoint, measured on the real 25 697-row table:
     *
     * <table><caption>measured</caption>
     *   <tr><th>term</th><th>proxy that admitted it</th><th>similarity() rechecks</th><th>ms</th></tr>
     *   <tr><td>{@code "•".repeat(50)}</td><td>whole-term length &ge; 3</td>
     *       <td>Seq Scan, 25 697 rows</td><td>92.5</td></tr>
     *   <tr><td>{@code "ка ".repeat(17)}</td><td>+ {@link #bearsTrigrams(String)} on the term</td>
     *       <td>10 070</td><td>47.5</td></tr>
     *   <tr><td>{@code "•к".repeat(25)}</td><td>+ a 3-character TOKEN floor</td>
     *       <td>3 022</td><td>21.7</td></tr>
     * </table>
     *
     * <p>Every one of those clears {@code @Size(50)}, the 3-character floor and a
     * "carries an alphanumeric somewhere" test, because none of those measures the thing that
     * decides selectivity. pg_trgm tokenises on alphanumeric runs and pads each one, so a run of
     * length L contributes L+1 keys of which only L-2 are interior; runs of length 1 and 2
     * contribute boundary keys ONLY, which are shared by thousands of names. At the 0.3 threshold a
     * term with three distinct keys needs just one to match, and «ка» alone put 10 070 rows into
     * the recheck. The run length is the only property an attacker cannot inflate by padding —
     * repeating a 2-character fragment fifty times adds no distinct key at all.
     *
     * <p><b>It costs no legitimate caller anything.</b> Exactly two of the 25 697 settlements have
     * no 3-character run — the two villages named «Яр» — and both are already unreachable under the
     * {@link #MIN_QUERY_LENGTH} floor that predates this. «Кам’янка», «Івано-Франківськ»,
     * «с. Нове» and «112» all carry a qualifying run and are unaffected; the measured worst
     * ADMITTED adversarial term after this change (a repeated 4-letter fragment) costs 10.1 ms
     * against 10.9 ms for the benign «іванівка», so an anonymous caller can no longer buy more work
     * than a real user already does.
     *
     * <p><b>Scope.</b> {@link #of(String)} deliberately still applies the weaker per-token rule:
     * it serves {@code /search/**} over the masters and salons tables, a different surface on a
     * different branch with three orders of magnitude fewer rows. Adopting this predicate there is
     * the right follow-up, not a change to smuggle through a Phase 326 audit.
     *
     * @param term a raw or normalised term; must not be {@code null}
     * @return {@code true} when a GIN trigram index can serve a predicate built from this term
     *         selectively
     */
    public static boolean hasIndexServableRun(String term) {
        return TRIGRAM_RUN.matcher(term).find();
    }

    /** {@code true} when no query was supplied — the {@code q} predicate is omitted entirely. */
    public boolean isAbsent() {
        return tokens.isEmpty() && !belowMinimumLength;
    }

    /** {@code true} when the query is executable and carries at least one token. */
    public boolean hasTokens() {
        return !tokens.isEmpty();
    }

    /**
     * Canonical cache-key form of a raw {@code q} — the value the
     * {@code @Cacheable} SpEL on {@code SearchService.searchMasters} /
     * {@code searchSalons} binds instead of the raw parameter.
     *
     * <h4>Why the raw string is the wrong key</h4>
     * {@link #of(String)} trims, collapses runs of Unicode whitespace, folds four
     * curly apostrophe variants onto {@code U+0027}, and caps the term count at
     * {@link #MAX_TOKENS}. So {@code "Ботокс  для"}, {@code "Ботокс для "},
     * {@code "Мар'яна"} and {@code "Мар’яна"} are four distinct raw strings that
     * execute byte-identical SQL and return byte-identical pages — four cache
     * entries where one belongs, on a 500-entry cache shared with the
     * location-only browse pages. Harmless while free-text search was broken; not
     * once it works, and an incremental search box is exactly the client that emits
     * these near-duplicate spellings.
     *
     * <h4>Why it is a String and not the token list</h4>
     * The key must keep the three outcomes of {@link #of(String)} apart, and two of
     * them carry an <b>empty</b> token list while returning completely different
     * pages: {@link #isAbsent()} yields the unfiltered location-scoped set,
     * {@link #belowMinimumLength()} yields an explicit empty page. Keying on
     * {@code tokens()} alone would collide those two and let {@code ?q=Ру} serve the
     * unfiltered set out of cache — re-introducing defect B through the cache layer.
     * The three states map to three disjoint key domains:
     *
     * <ul>
     *   <li>absent → {@code null}</li>
     *   <li>below minimum length → {@code ""} (every too-short query returns the
     *       same empty page, so they legitimately share one entry)</li>
     *   <li>executable → the tokens joined by a single space, which is injective
     *       because tokenisation split on whitespace and so no token contains any</li>
     * </ul>
     *
     * <p>Token order is preserved rather than sorted. Sorting would raise the hit
     * rate a little ({@code "Ботокс для"} ≡ {@code "для Ботокс"} under the ANDed
     * token semantics), but an unsorted key can only ever cost a miss, never serve a
     * wrong page — and it does not silently depend on the predicate staying
     * order-insensitive.</p>
     *
     * @param rawQuery the raw {@code q} request parameter
     * @return the canonical key, or {@code null} when no query was supplied
     */
    public static String cacheKey(String rawQuery) {
        NormalizedSearchQuery normalized = of(rawQuery);
        return normalized.isAbsent() ? null : String.join(" ", normalized.tokens());
    }
}
