package com.beautica.location.service;

import com.beautica.location.dto.SettlementSearchResponse;
import com.beautica.location.repository.CityRepository;
import com.beautica.search.service.NormalizedSearchQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The settlement autocomplete behind the {@code permitAll}
 * {@code GET /api/v1/settlements?query=...} (Phase 326).
 *
 * <p><b>Why this is a separate service from {@link LocationQueryService}.</b> That service owns the
 * oblast -> city -> district CASCADE, a transitional surface this endpoint RETIRES (mobile Phase
 * 346). They read the same table and nothing else: the cascade answers "what is inside this
 * parent", this answers "what did the user mean by these letters", with its own normalisation,
 * ranking and index. Folding them together would grow an already 200-line class and couple a
 * surface that is being removed to the one replacing it.
 *
 * <p><b>Three states, kept apart</b> — the distinction {@link NormalizedSearchQuery} exists to
 * enforce on the sibling discovery search, applied here:
 * <ul>
 *   <li><b>absent / blank</b> -> the 50 {@code is_major} settlements (phase-326 D3). This is the
 *       "biggest cities first" list the user sees before typing, not an empty result.</li>
 *   <li><b>not servable by the index</b> -> an EXPLICIT empty list, with no query issued. Never
 *       the major list, and never an unfiltered scan. ONE predicate decides this,
 *       {@link NormalizedSearchQuery#hasIndexServableRun(String)}: the term must contain an
 *       uninterrupted alphanumeric RUN of {@link NormalizedSearchQuery#MIN_QUERY_LENGTH}
 *       characters. Three weaker spellings each admitted the input the next one had to catch —
 *       a whole-term length floor admits {@code "•".repeat(50)} (empty key set, Seq Scan, 92 ms);
 *       adding a whole-term {@code bearsTrigrams} still admits {@code "ка ".repeat(17)} (three
 *       distinct keys, 10 070 rechecks, 47 ms); adding a 3-character TOKEN floor still admits
 *       {@code "•к".repeat(25)} (two distinct keys, 3 022 rechecks, 22 ms). The RUN is the only
 *       form padding cannot defeat, because repeating a sub-trigram fragment adds no distinct
 *       key.</li>
 *   <li><b>executable</b> -> the ranked search, capped at {@link #MAX_RESULTS}.</li>
 * </ul>
 *
 * <p><b>The 3-character floor is {@link NormalizedSearchQuery#MIN_QUERY_LENGTH}, imported rather
 * than redeclared</b> (phase-326 D2). It is the same rule for the same reason: a GIN
 * {@code pg_trgm} index needs one full 3-gram to serve either the {@code ILIKE} prefix or the
 * {@code %} similarity predicate, and a 1-2 character term matches no trigram and degrades into a
 * sequential scan of 25 698 rows on an unauthenticated endpoint. A second constant here would be
 * free to drift from the one the mobile debounce is written against.
 *
 * <p><b>The length floor is necessary and was NOT sufficient.</b> It is a proxy for "the GIN index
 * can serve this", and a 100-character string of bullets satisfies the proxy while failing the
 * thing: {@code pg_trgm} tokenises on alphanumeric runs, so {@code show_trgm('•••') = {}} and BOTH
 * tiers degrade into the sequential scan the floor exists to prevent — measured at 119 ms with
 * {@code Rows Removed by Filter: 25697}, against 3.4 ms for «нов». One IP's full 240-token minute
 * held the entire Hikari pool for 6.02 s, unauthenticated, which then queues logins and booking
 * writes behind {@code connection-timeout: 20000}. {@link #search(String)} therefore requires
 * {@link NormalizedSearchQuery#hasIndexServableRun(String)} before it issues anything — which
 * SUBSUMES the length floor rather than sitting beside it, because a 3-character alphanumeric run
 * is by construction at least 3 characters long. The controller's {@code @Pattern} does not cover
 * this: it bans Cc/Cf/Zl/Zp, and punctuation is none of those.
 *
 * <p>Below-minimum returns 200 + an empty list rather than 400, matching
 * {@code SearchController}'s already-shipped contract: a 2-character query is a legitimate
 * keystroke in an incremental search box, not malformed input, and the phase's acceptance
 * ("400 or empty, never a scan") permits either. The user-facing hint is attached by the
 * controller, where the rest of the HTTP presentation lives.
 *
 * <p><b>Caching.</b> Only the pre-typing major list is cached — a single fixed key over Flyway-seed
 * data that cannot change at runtime, so it follows the same 24h-TTL / no-eviction contract the
 * {@code location*} caches document, with {@code sync = true} because it is the hottest key on an
 * unauthenticated endpoint (§F-7). The per-query results are deliberately NOT cached: the key
 * space is every prefix a user can type, so a bounded Caffeine cache would be churned out of
 * usefulness by ordinary typing and could be filled with junk keys by an anonymous caller (§A,
 * Caffeine slot exhaustion). The query is index-served in single-digit milliseconds and the
 * endpoint is IP-throttled, which is the correct control for that shape.
 *
 * <p><b>No occupation predicate</b> (phase-325 D3 / phase-326 D4): there is no
 * {@code occupation_status} column and no occupied row in {@code cities} — Phase 324's exclusion
 * set is applied to the import SOURCE, so the ban is an ABSENCE invariant enforced by
 * {@code V171__import_free_settlements}'s pre-filtered CSV. This service is NOT its enforcement
 * surface and must never grow a filter that implies it is.
 */
@Service
@RequiredArgsConstructor
public class SettlementSearchService {

    /** Cache name for the pre-typing major-settlement list. Registered in {@code CacheConfig}. */
    static final String CACHE_MAJOR_SETTLEMENTS = "settlementMajors";

    /** Hard result cap for a typed query (phase-326 D8). Applied as a SQL {@code LIMIT}. */
    static final int MAX_RESULTS = 20;

    /**
     * Explicit trigram floor for the fallback tier, mirroring PostgreSQL's default
     * {@code pg_trgm.similarity_threshold}.
     *
     * <p>The indexable {@code %} operator compares against that SESSION GUC, which this
     * application never sets. Binding the same floor explicitly keeps the result set deterministic
     * if the GUC is ever LOWERED; a RAISED GUC would narrow recall without tripping anything here,
     * which is why {@code SettlementSearchIT} asserts the effective threshold instead of assuming
     * it.
     */
    static final double MIN_SIMILARITY = 0.3d;

    /** Unicode-aware so a non-breaking space collapses like a plain one. */
    private static final Pattern WHITESPACE =
            Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    /**
     * Every apostrophe variant a keyboard can emit, folded onto {@link #STORED_APOSTROPHE}.
     *
     * <p><b>This fold points the OPPOSITE way to
     * {@link NormalizedSearchQuery#foldApostrophes(String)}, and that is not an oversight.</b>
     * That method folds onto {@code U+0027} because {@code users.first_name} and
     * {@code salons.name} store the straight form. {@code cities.name_uk} does not: the KATOTTH
     * import writes {@code U+2019} RIGHT SINGLE QUOTATION MARK, and on the migrated table 589 rows
     * carry {@code U+2019} and ZERO carry {@code U+0027} («Кам’янка», «Слов’янськ»). Reusing the
     * sibling fold here would turn every desktop-keyboard apostrophe into a character the column
     * never contains and silently drop those 589 settlements from the PREFIX tier.
     *
     * <p>The similarity tiers are unaffected either way — pg_trgm treats both characters as word
     * separators — so this fold exists solely for {@code ILIKE}. {@code SettlementSearchIT} pins
     * the stored form so a future re-import that switches to {@code U+0027} fails loudly here
     * rather than degrading the picker.
     */
    private static final Pattern APOSTROPHES = Pattern.compile("['ʼ‘´]");

    /** {@code U+2019} — the apostrophe {@code cities.name_uk} actually stores. */
    private static final String STORED_APOSTROPHE = "’";

    private final CityRepository cityRepository;

    /**
     * Self-proxy reference so both delegations out of {@link #search(String)} reach the Spring AOP
     * proxy: {@link #listMajorSettlements()} for its {@code @Cacheable}, and
     * {@link #runIndexedSearch(String)} for its {@code @Transactional}. A direct {@code this.}
     * call bypasses the proxy entirely (§F-3: self-invocation does not trigger AOP), so the cache
     * would be configured, registered, metered — and never read or written, and the read-only
     * transaction would silently not exist, with nothing failing to say so either way.
     *
     * <p>Deliberate, documented exception to the project's no-field-injection rule, mirroring
     * {@code AuthService#self} and {@code NotificationOutboxDrainWorker#self}: a self-reference
     * cannot be a constructor parameter (circular dependency at construction time), and splitting
     * a 2-method service into two beans to express it would cost more than it buys.
     */
    @Autowired
    @Lazy
    private SettlementSearchService self;

    /**
     * Resolves one autocomplete keystroke into at most {@link #MAX_RESULTS} ranked settlements.
     *
     * @param rawQuery the caller's {@code query} parameter, exactly as received; {@code null},
     *                 blank and too-short values are all legitimate inputs here
     * <p><b>Deliberately NOT {@code @Transactional}.</b> This is the admission stage, and both of
     * its rejections return without touching the database — so wrapping it would open a transaction,
     * bind an EntityManager and register a synchronization for every refused request, on precisely
     * the path an attacker drives at 240/min. Hibernate 6's delayed connection acquisition means no
     * JDBC connection is taken, so the waste is CPU and allocation only, but it is waste bought on
     * the hostile path and it is free to avoid. The transaction begins where the query does, in
     * {@link #runIndexedSearch(String)} — reached through {@link #self} so the proxy applies.
     *
     * @return the major list for a blank query, an empty list for a term the trigram index cannot
     *         serve (too short, or carrying no trigram at all), otherwise the ranked matches
     */
    public List<SettlementSearchResponse> search(String rawQuery) {
        String term = normalize(rawQuery);
        if (term.isEmpty()) {
            return self.listMajorSettlements();
        }
        if (!NormalizedSearchQuery.hasIndexServableRun(term)) {
            return List.of();
        }
        return self.runIndexedSearch(term);
    }

    /**
     * The ranked query itself, for a term {@link #search(String)} has already admitted.
     *
     * <p>Public only because it is invoked through the {@link #self} proxy; not part of the HTTP
     * surface, which exposes one endpoint with one optional parameter. It must never be called with
     * an un-admitted term — the run guard in {@code search} is what keeps a zero-trigram or
     * padding-only term from reaching a sequential scan of 25 698 rows, and this method repeats none
     * of it.
     *
     * @param term an already-normalised term that {@link NormalizedSearchQuery#hasIndexServableRun}
     *             accepts
     * @return at most {@link #MAX_RESULTS} ranked settlements
     */
    @Transactional(readOnly = true)
    public List<SettlementSearchResponse> runIndexedSearch(String term) {
        return cityRepository
                .searchByName(likePrefixPattern(term), term, MIN_SIMILARITY, MAX_RESULTS)
                .stream()
                .map(SettlementSearchResponse::from)
                .toList();
    }

    /**
     * The 50 curated {@code is_major} settlements, ordered by Ukrainian name — the list shown
     * before the user types anything (phase-326 D3).
     *
     * <p>Public because {@link #search(String)} reaches it through {@link #self}; not part of the
     * HTTP surface, which exposes one endpoint with one optional parameter.
     *
     * <p>Same static-reference-data cache contract as the {@code location*} reads: 24h TTL, no
     * {@code @CacheEvict} path, because the rows are written only by Flyway and the only
     * invalidation is a redeploy — which is also the only time the seed can change.
     * {@code sync = true} collapses the cold-key stampede on what is, by construction, the single
     * hottest key of an unauthenticated endpoint (§F-7).
     *
     * @return all major settlements with their oblast labels
     */
    @Cacheable(value = CACHE_MAJOR_SETTLEMENTS, sync = true)
    @Transactional(readOnly = true)
    public List<SettlementSearchResponse> listMajorSettlements() {
        return cityRepository.findMajorSettlements().stream()
                .map(SettlementSearchResponse::from)
                .toList();
    }

    /**
     * Whether a caller's term was typed but cannot be served by a trigram index — the state that
     * yields an EXPLICIT empty list plus a hint, never the major list and never a scan.
     *
     * <p>Public and static because {@code SettlementSearchController} needs the same answer to
     * decide whether to attach its user-facing hint, and a second check written at the HTTP layer
     * would be free to disagree with this one: the comparison runs on the NORMALISED term, whose
     * whitespace runs are already collapsed, so a raw {@code query.trim().length()} would classify
     * «а&nbsp;&nbsp;б» differently from this method. One definition, two readers.
     *
     * <p><b>This is now the SAME predicate {@link #search(String)} refuses on</b>, and that
     * identity is the point. It was previously a length-only test, independent of the two gates
     * {@code search} actually applied, so "the endpoint returned nothing" and "the user was told
     * why" could drift apart — and did: a 50-character punctuation run returned a silent empty
     * list with no hint. Both readers now ask
     * {@link NormalizedSearchQuery#hasIndexServableRun(String)}.
     *
     * <p>A blank query is NOT unservable: it is the deliberate pre-typing state that returns the
     * major list (phase-326 D3), which is why the emptiness check comes first.
     *
     * @param rawQuery the caller's parameter, exactly as received; may be {@code null}
     * @return {@code true} only when something was typed and no token in it can drive the GIN index
     */
    public static boolean isNotIndexServable(String rawQuery) {
        String term = normalize(rawQuery);
        return !term.isEmpty() && !NormalizedSearchQuery.hasIndexServableRun(term);
    }

    /**
     * Trims, collapses internal whitespace runs and folds apostrophes onto the form
     * {@code cities.name_uk} stores.
     *
     * <p>Whitespace collapsing matters to the trigram tier and not only to tidiness: pg_trgm pads
     * word boundaries, so {@code 'іван  фран'} and {@code 'іван фран'} would otherwise produce
     * different trigram sets and different scores for the same intent.
     *
     * @param rawQuery the caller's parameter; may be {@code null}
     * @return the normalised term, or an empty string when nothing was typed
     */
    private static String normalize(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return "";
        }
        String collapsed = WHITESPACE.matcher(rawQuery.trim()).replaceAll(" ");
        return APOSTROPHES.matcher(collapsed).replaceAll(STORED_APOSTROPHE);
    }

    /**
     * Builds the tier-1 {@code ILIKE} pattern: the normalised term with its own {@code LIKE}
     * metacharacters neutralised, plus a trailing {@code %}.
     *
     * <p>A user-typed {@code %}, {@code _} or {@code \} must match that literal character instead
     * of acting as a wildcard — otherwise a single {@code %} turns the prefix tier into
     * "everything", promoting 25 698 rows into tier 1 and making the ranking meaningless.
     * {@code \} is escaped FIRST so the escapes introduced for {@code %} and {@code _} are not
     * themselves re-escaped; PostgreSQL's {@code LIKE} honours {@code \} as the escape character
     * by default, so no {@code ESCAPE} clause is needed.
     *
     * <p>Deliberately a local helper rather than a shared one: {@code SearchService#likeContains}
     * builds a {@code %term%} CONTAINMENT pattern for a different table and different columns, and
     * this is the second occurrence of the three-line escape, not the third (project DRY rule:
     * extract on the third repetition). If a third appears, promote the escape to {@code common/}
     * and rewire both.
     *
     * @param term the already-normalised term; never {@code null} or empty
     * @return a prefix {@code LIKE} pattern safe to bind
     */
    private static String likePrefixPattern(String term) {
        String escaped = term
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return escaped + "%";
    }
}
