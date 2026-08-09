package com.beautica.search.service;

import com.beautica.search.dto.SearchResultWindow;

import java.util.List;
import java.util.Map;

/**
 * The single definition of the six discovery cache names (four browse/free-text result
 * caches plus the two filter-scoped total memos), and of the
 * browse&nbsp;&rarr;&nbsp;free-text routing map that {@link SearchCacheResolver}
 * applies.
 *
 * <h3>Why the population is split (perf + security audit 2026-07-29)</h3>
 * {@code /search/masters} and {@code /search/salons} used to share ONE 500-entry
 * cache per surface, holding two structurally different key populations:
 *
 * <ul>
 *   <li><b>browse</b> — location-only (city/district &times; category &times; sort
 *       &times; price &times; page). Bounded by that product, <em>shared across every
 *       visitor</em>, and therefore the high-value entries: one load serves everybody
 *       who opens the discovery screen for that district.</li>
 *   <li><b>free-text ({@code q})</b> — <em>unbounded</em> in cardinality. An
 *       incremental search box emits roughly one request per settled keystroke, so
 *       typing «ламінування вій» mints ~13 distinct keys of which only the last is
 *       ever read again, and the discovery screen queries masters AND salons.</li>
 * </ul>
 *
 * On one shared cache the unbounded population evicts the bounded one: an
 * unauthenticated caller varying {@code q} converts <em>other</em> users' cached
 * browse pages back into DB work. The observable symptom of an undersized search
 * cache was therefore a latency regression on <b>browse</b>, not on search — a
 * failure mode a code reading does not surface.
 *
 * <p>Splitting also makes the hit-rate meters interpretable. A single gauge
 * averaged two populations with completely different expected hit rates, which is
 * exactly why the 500-entry cap could not be tuned from it. Each half now reports
 * its own rate, so the caps can be argued from data rather than guessed.</p>
 *
 * <h3>Blanket-eviction contract</h3>
 * The two write paths that clear discovery wholesale
 * ({@code SalonService.deactivateSalon}, {@code UserService} name/locality writes for
 * an {@code INDEPENDENT_MASTER}) must clear <b>both</b> halves of their surface —
 * clearing only one leaves a stale row reachable through the other. They iterate
 * {@link #MASTERS_ALL} / {@link #SALONS_ALL} rather than naming caches inline so a
 * future third partition cannot be missed at a call site.
 */
public final class SearchCacheNames {

    /** Location-only master discovery: bounded key space, shared across all callers. */
    public static final String MASTERS_BROWSE = "search:masters:browse";

    /** Free-text master discovery: unbounded key space, low reuse. */
    public static final String MASTERS_QUERY = "search:masters:q";

    /** Location-only salon discovery: bounded key space, shared across all callers. */
    public static final String SALONS_BROWSE = "search:salons:browse";

    /** Free-text salon discovery: unbounded key space, low reuse. */
    public static final String SALONS_QUERY = "search:salons:q";

    /**
     * Filter-scoped {@code totalElements} memo for master discovery (perf follow-up,
     * architect decision — see {@code SearchService#searchMasters}'s Javadoc note on the
     * total-memo trade-off). Keyed on the filter tuple ONLY (no page/size), so it holds
     * ONE entry per distinct filter regardless of how many pages a caller sweeps.
     *
     * <p><b>Deliberately bypasses {@code SearchCacheResolver}'s browse/query routing.</b>
     * A total is identical whether the request that produced it was a browse (no {@code q})
     * or a free-text query — the routing split exists to protect the BROWSE result-page
     * population from the unbounded free-text key churn, and that concern does not apply to
     * a single {@code Long} value. One cache therefore serves both request shapes.</p>
     *
     * <p>Not gated by {@code pageNumber < 5}: unlike the browse/query result caches, this is
     * read/written directly through {@code CacheManager} (a read-then-maybe-skip, not a
     * memoized return value), specifically so it keeps working on page &ge; 5 — the population
     * {@link SearchResultWindow} identifies as the expensive, uncached sweep. See
     * {@code CacheConfig} for sizing (1000 entries / 60 s TTL — mirrors the browse TTL since a
     * stale total is tolerated up to that same window).</p>
     */
    public static final String MASTERS_TOTAL = "search:masters:total";

    /**
     * Filter-scoped {@code totalElements} memo for salon discovery — mirrors
     * {@link #MASTERS_TOTAL}; see its Javadoc for the full rationale.
     */
    public static final String SALONS_TOTAL = "search:salons:total";

    /** Every master-discovery cache — iterate this for blanket eviction. */
    public static final List<String> MASTERS_ALL = List.of(MASTERS_BROWSE, MASTERS_QUERY);

    /** Every salon-discovery cache — iterate this for blanket eviction. */
    public static final List<String> SALONS_ALL = List.of(SALONS_BROWSE, SALONS_QUERY);

    /**
     * Maps the cache declared on the {@code @Cacheable} annotation (always the
     * browse half) to its free-text sibling. {@link SearchCacheResolver} looks the
     * declared name up here when the request carries a normalised {@code q}; an
     * unmapped name is a wiring error and fails loudly rather than silently routing
     * every free-text key back onto the browse cache.
     */
    static final Map<String, String> QUERY_CACHE_BY_BROWSE_CACHE = Map.of(
            MASTERS_BROWSE, MASTERS_QUERY,
            SALONS_BROWSE, SALONS_QUERY);

    private SearchCacheNames() {
        // constant holder
    }
}
