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
        String folded = CURLY_APOSTROPHES.matcher(rawQuery).replaceAll(STRAIGHT_APOSTROPHE);
        List<String> retained = WHITESPACE.splitAsStream(folded.trim())
                .filter(token -> !token.isEmpty())
                .limit(MAX_TOKENS)
                .toList();
        if (retained.isEmpty()) {
            // Only reachable for input that is entirely Unicode whitespace not
            // covered by String#isBlank — treat it as "nothing was typed".
            return ABSENT;
        }
        boolean trigramServable = retained.stream().anyMatch(token -> token.length() >= MIN_QUERY_LENGTH);
        return trigramServable ? new NormalizedSearchQuery(retained, false) : BELOW_MINIMUM;
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
