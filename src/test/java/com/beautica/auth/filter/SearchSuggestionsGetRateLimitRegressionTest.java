package com.beautica.auth.filter;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BandwidthBuilder;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.TimeMeter;
import io.github.bucket4j.local.LocalBucket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>REGRESSION NET — per-IP throttle on GET /api/v1/search/suggestions (Phase 331 autocomplete).</b>
 *
 * <p>Mirrors {@link SettlementSearchGetRateLimitRegressionTest} exactly — same rationale (a
 * {@code permitAll}, per-keystroke autocomplete box with no test at all is a cap that could be
 * deleted and nothing would say so), same structure (real filter through
 * {@code doFilterInternal}, refill strategy asserted separately over a controllable
 * {@link TimeMeter}). The one property THIS class exists to catch that the settlement sibling
 * cannot: {@code /api/v1/search/suggestions} also starts with {@code /api/v1/search/}, so the
 * exact-path branch for it MUST run BEFORE the {@code SEARCH_PATH_PREFIX} branch in
 * {@code doFilterInternal} — see {@link #should_keepASeparateBudget_when_theSearchResultsBucketIsAlreadyExhausted}
 * and its explicit falsification instruction.
 */
@DisplayName("AuthRateLimitFilter — GET /search/suggestions per-IP throttle (Phase 331 regression net)")
class SearchSuggestionsGetRateLimitRegressionTest {

    private static final String REMOTE_ADDR = "10.0.0.79";
    private static final String SUGGESTIONS_PATH = "/api/v1/search/suggestions";
    private static final String MASTERS_SEARCH_PATH = "/api/v1/search/masters";

    /** The documented per-IP ceiling — the SUSTAINED rate, reached only after a full window. */
    private static final int EXPECTED_CAPACITY = 240;

    /** The documented FIRST-CONTACT grant — a quarter of the capacity, same shape as settlements. */
    private static final int EXPECTED_INITIAL_TOKENS = EXPECTED_CAPACITY / 4;

    /** The documented window the capacity refills over. */
    private static final Duration EXPECTED_WINDOW = Duration.ofMinutes(1);

    /** Fired above the cap so the bucket is certain to be exhausted within the loop. */
    private static final int REQUESTS_TO_FIRE = EXPECTED_CAPACITY * 2;

    private static LoadingCache<String, Bucket> permissive() {
        return Caffeine.newBuilder().build(key -> Bucket.builder()
                .addLimit(BandwidthBuilder.builder()
                        .capacity(1_000_000)
                        .refillIntervally(1_000_000, Duration.ofMinutes(1))
                        .build())
                .build());
    }

    /**
     * The real filter with all 22 {@code @Qualifier} buckets made permissive, so the only cap
     * that can fire is the internally-built suggestions bucket under test (or, for the isolation
     * tests, the internally-built searchBuckets sibling — also otherwise unthrottled here since
     * it too is built internally, not one of the 22 injected args).
     */
    private AuthRateLimitFilter realFilter() {
        return new AuthRateLimitFilter(
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(), permissive(), permissive());
    }

    private MockHttpServletRequest get(String path) {
        var request = new MockHttpServletRequest("GET", path);
        request.setRemoteAddr(REMOTE_ADDR);
        return request;
    }

    /**
     * Fires GETs at {@code path} until the first 429 and returns how many were allowed. Bounded by
     * {@link #REQUESTS_TO_FIRE} so a MISSING throttle fails loudly instead of looping forever.
     */
    private int countAllowedBefore429(AuthRateLimitFilter filter, MockHttpServletRequest request)
            throws Exception {
        int allowed = 0;
        for (int i = 0; i < REQUESTS_TO_FIRE; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilterInternal(request, response, new MockFilterChain());
            if (response.getStatus() == 429) {
                return allowed;
            }
            allowed++;
        }
        throw new AssertionError("no 429 within " + REQUESTS_TO_FIRE
                + " requests — the search-suggestions throttle is missing for " + request.getRequestURI());
    }

    private static void assertAtFirstContactGrant(int allowed, String because) {
        assertThat(allowed)
                .as(because)
                .isBetween(EXPECTED_INITIAL_TOKENS, EXPECTED_INITIAL_TOKENS + 1);
    }

    // ── the cap ───────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("should_return429_when_suggestionsGetExceedsTheFirstContactBurst")
    void should_return429_when_suggestionsGetExceedsTheFirstContactBurst() throws Exception {
        AuthRateLimitFilter filter = realFilter();

        int allowed = countAllowedBefore429(filter, get(SUGGESTIONS_PATH));

        assertAtFirstContactGrant(allowed,
                "a never-seen IP must NOT arrive holding the whole %d-token minute"
                        .formatted(EXPECTED_CAPACITY));
    }

    @Test
    @DisplayName("should_notForwardThrottledRequest_when_suggestionsGetIsOverCap")
    void should_notForwardThrottledRequest_when_suggestionsGetIsOverCap() throws Exception {
        AuthRateLimitFilter filter = realFilter();

        MockHttpServletResponse lastResponse = null;
        MockFilterChain lastChain = null;
        for (int i = 0; i < REQUESTS_TO_FIRE; i++) {
            lastResponse = new MockHttpServletResponse();
            lastChain = new MockFilterChain();
            filter.doFilterInternal(get(SUGGESTIONS_PATH), lastResponse, lastChain);
            if (lastResponse.getStatus() == 429) {
                break;
            }
        }

        assertThat(lastResponse.getStatus()).isEqualTo(429);
        assertThat(lastChain.getRequest())
                .as("a throttled suggestions lookup must NOT reach the controller")
                .isNull();
    }

    @Test
    @DisplayName("should_setRetryAfterHeader_when_suggestionsGetThrottled")
    void should_setRetryAfterHeader_when_suggestionsGetThrottled() throws Exception {
        AuthRateLimitFilter filter = realFilter();

        MockHttpServletResponse lastResponse = null;
        for (int i = 0; i < REQUESTS_TO_FIRE; i++) {
            lastResponse = new MockHttpServletResponse();
            filter.doFilterInternal(get(SUGGESTIONS_PATH), lastResponse, new MockFilterChain());
            if (lastResponse.getStatus() == 429) {
                break;
            }
        }

        assertThat(lastResponse.getStatus()).isEqualTo(429);
        assertThat(lastResponse.getHeader("Retry-After")).isNotNull();
        assertThat(lastResponse.getContentType()).contains("application/json");
    }

    // ── bucket isolation — the whole reason this bucket exists ───────────────────────────────

    @Test
    @DisplayName("should_keepASeparateBudget_when_theSearchResultsBucketIsAlreadyExhausted")
    void should_keepASeparateBudget_when_theSearchResultsBucketIsAlreadyExhausted() throws Exception {
        // The sizing note's whole argument for a carved-out bucket: typing in the suggestions box
        // must not compete with a concurrent /search/masters results-page read for the same
        // 240-token budget. If the exact-path branch were ever placed AFTER the SEARCH_PATH_PREFIX
        // branch in doFilterInternal, this assertion goes red — the prefix branch would already
        // have consumed (and exhausted) searchBuckets on the suggestions path too.
        AuthRateLimitFilter filter = realFilter();
        countAllowedBefore429(filter, get(MASTERS_SEARCH_PATH));

        int suggestionsAllowed = countAllowedBefore429(filter, get(SUGGESTIONS_PATH));

        assertAtFirstContactGrant(suggestionsAllowed,
                "draining the /search/masters results budget must leave the suggestions budget intact "
                        + "— FALSIFY by moving the SEARCH_SUGGESTIONS_PATH branch after the "
                        + "SEARCH_PATH_PREFIX branch in doFilterInternal: this must then fail because "
                        + "the prefix branch will have already consumed and returned for every "
                        + "suggestions request too");
    }

    @Test
    @DisplayName("should_keepTheSearchResultsBudgetIntact_when_theSuggestionsBucketIsAlreadyExhausted")
    void should_keepTheSearchResultsBudgetIntact_when_theSuggestionsBucketIsAlreadyExhausted() throws Exception {
        AuthRateLimitFilter filter = realFilter();
        countAllowedBefore429(filter, get(SUGGESTIONS_PATH));

        int mastersAllowed = countAllowedBefore429(filter, get(MASTERS_SEARCH_PATH));

        // Unlike the suggestions/settlement buckets, searchBuckets (searchBandwidth()) starts
        // FULL at capacity — a deliberate, documented asymmetry (see searchBandwidth()'s javadoc:
        // /search/**'s bounded token-shape means a burst control has nothing to bound). So the
        // control value here is the full 240, not a quarter-capacity first-contact grant.
        assertThat(mastersAllowed)
                .as("draining the suggestions budget must leave /search/masters' own (full-capacity) "
                        + "budget intact")
                .isEqualTo(EXPECTED_CAPACITY);
    }

    @Test
    @DisplayName("should_passThrough_when_pathIsUnderSearchButNotTheExactSuggestionsRoute")
    void should_passThrough_when_pathIsUnderSearchButNotTheExactSuggestionsRoute() throws Exception {
        // The branch matches EXACTLY, not by prefix, mirroring settlements' own exact-path
        // rationale — a future /search/suggestions/{id} subtree must not silently inherit this
        // budget; it falls through to the SEARCH_PATH_PREFIX branch (searchBuckets) instead, a
        // real but DIFFERENT throttle. ONE request is enough to prove the routing — repeating it
        // would exhaust searchBuckets' own real cap (a different bucket than the one under test in
        // this class) and turn this into a flaky 429 on a later iteration.
        AuthRateLimitFilter filter = realFilter();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(get(SUGGESTIONS_PATH + "/extra"), response, chain);

        assertThat(chain.getRequest())
                .as("the exact-path guard must not silently adopt a longer path into this budget — "
                        + "it must still reach the controller via the (separate) searchBuckets prefix throttle")
                .isNotNull();
    }

    // ── the refill strategy ───────────────────────────────────────────────────────────────────

    private static final class ControllableTime implements TimeMeter {

        private final AtomicLong nanos = new AtomicLong();

        @Override public long currentTimeNanos() {
            return nanos.get();
        }

        @Override public boolean isWallClockBased() {
            return false;
        }

        void advance(Duration duration) {
            nanos.addAndGet(duration.toNanos());
        }
    }

    @Test
    @DisplayName("should_declareGreedyRefill_when_theSuggestionsBandwidthIsBuilt")
    void should_declareGreedyRefill_when_theSuggestionsBandwidthIsBuilt() {
        Bandwidth bandwidth = AuthRateLimitFilter.searchSuggestionBandwidth();

        assertThat(bandwidth.isGready()).isTrue();
        assertThat(bandwidth.isRefillIntervally()).isFalse();
        assertThat(bandwidth.getCapacity()).isEqualTo(EXPECTED_CAPACITY);
        assertThat(bandwidth.getRefillPeriodNanos()).isEqualTo(EXPECTED_WINDOW.toNanos());
    }

    @Test
    @DisplayName("should_notStartFull_when_anIpIsSeenForTheFirstTime")
    void should_notStartFull_when_anIpIsSeenForTheFirstTime() {
        LocalBucket bucket = Bucket.builder()
                .addLimit(AuthRateLimitFilter.searchSuggestionBandwidth())
                .withCustomTimePrecision(new ControllableTime())
                .build();

        assertThat(bucket.getAvailableTokens())
                .isEqualTo(EXPECTED_INITIAL_TOKENS)
                .isLessThan(EXPECTED_CAPACITY);
    }

    @Test
    @DisplayName("should_reachTheFullCeiling_when_aWindowHasPassedSinceFirstContact")
    void should_reachTheFullCeiling_when_aWindowHasPassedSinceFirstContact() {
        ControllableTime time = new ControllableTime();
        LocalBucket bucket = Bucket.builder()
                .addLimit(AuthRateLimitFilter.searchSuggestionBandwidth())
                .withCustomTimePrecision(time)
                .build();

        time.advance(EXPECTED_WINDOW);

        assertThat(bucket.getAvailableTokens()).isEqualTo(EXPECTED_CAPACITY);
    }

    @Test
    @DisplayName("should_dripTokensAcrossTheWindow_when_theBucketHasBeenDrained")
    void should_dripTokensAcrossTheWindow_when_theBucketHasBeenDrained() {
        ControllableTime time = new ControllableTime();
        LocalBucket bucket = Bucket.builder()
                .addLimit(AuthRateLimitFilter.searchSuggestionBandwidth())
                .withCustomTimePrecision(time)
                .build();
        bucket.tryConsumeAsMuchAsPossible();

        time.advance(EXPECTED_WINDOW.dividedBy(EXPECTED_CAPACITY));

        assertThat(bucket.getAvailableTokens()).isEqualTo(1);
    }
}
