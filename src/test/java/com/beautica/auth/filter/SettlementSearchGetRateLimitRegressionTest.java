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
 * <b>REGRESSION NET — per-IP throttle on GET /api/v1/settlements (Phase 326 autocomplete).</b>
 *
 * <p>The settlement autocomplete shipped with a 240 / 60 s bucket and <b>no test whatsoever</b>.
 * Every sibling bucket in {@link AuthRateLimitFilter} has one — {@code SearchGetRateLimitRegressionTest},
 * {@code CatalogueBrowseGetRateLimitRegressionTest}, {@code GuestAvailabilityGetRateLimitTest},
 * {@code InviteValidateGetRateLimitTest} — and 132 {@code auth.filter} tests ran green without one
 * line of them touching this arm: the cap could have been deleted and nothing would have said so.
 * That matters more here than on most of those endpoints, because this one is {@code permitAll} and
 * its only other defences are inside the service.
 *
 * <p>Like its siblings this drives the REAL filter through {@code doFilterInternal} and asserts
 * observable HTTP behaviour. {@link #EXPECTED_CAPACITY} is deliberately restated rather than read
 * off the production constant: the cap is a product decision about legitimate client behaviour, so
 * a silent change to it should fail here and be re-argued.
 *
 * <p>The refill STRATEGY is asserted separately, against a bucket built from the production
 * {@link AuthRateLimitFilter#settlementSearchBandwidth()} over a controllable {@link TimeMeter}.
 * It cannot be observed through {@code doFilterInternal}: distinguishing a step refill from a
 * greedy one means watching the bucket across a fraction of its window, which through the filter
 * would need either a {@code Thread.sleep} (banned) or a real 60-second wait.
 */
@DisplayName("AuthRateLimitFilter — GET /settlements per-IP throttle (Phase 326 regression net)")
class SettlementSearchGetRateLimitRegressionTest {

    private static final String REMOTE_ADDR = "10.0.0.77";
    private static final String SETTLEMENTS_PATH = "/api/v1/settlements";
    private static final String SEARCH_PATH = "/api/v1/search/masters";

    /** The documented per-IP ceiling — the SUSTAINED rate, reached only after a full window. */
    private static final int EXPECTED_CAPACITY = 240;

    /**
     * The documented FIRST-CONTACT grant — a quarter of the capacity. A never-seen IP starts here,
     * not at {@link #EXPECTED_CAPACITY}, so this is the number an unpaced loop through the filter
     * observes. Restated rather than read off the production constant for the same reason the
     * capacity is: it is a decision about tolerable burst, and a silent change to it should fail
     * here and be re-argued.
     */
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
     * The real filter with all 22 {@code @Qualifier} buckets made permissive, so the only cap that
     * can fire is the internally-built settlement bucket under test.
     */
    private AuthRateLimitFilter realFilter() {
        return new AuthRateLimitFilter(
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(), permissive(), permissive(), permissive());
    }

    private MockHttpServletRequest get(String path) {
        var request = new MockHttpServletRequest("GET", path);
        request.setRemoteAddr(REMOTE_ADDR);
        return request;
    }

    /**
     * Fires GETs at {@code path} until the first 429 and returns how many were allowed. Bounded by
     * {@link #REQUESTS_TO_FIRE} so a MISSING throttle fails loudly instead of looping forever —
     * the exact failure mode this class exists to catch.
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
                + " requests — the settlement-autocomplete throttle is missing for " + request.getRequestURI());
    }

    // ── the cap ───────────────────────────────────────────────────────────────────────────────

    /**
     * Asserts a filter-driven allowance against the first-contact grant.
     *
     * <p>Bounded rather than exact, and deliberately by exactly one token: the bucket runs on the
     * real system clock here, so a loop that happens to straddle a 250 ms drip boundary legitimately
     * sees one extra token. The window this tolerates ({@code 60..61}) is nowhere near the
     * {@code 240} an un-capped initial grant produces, which is the regression under test — and the
     * EXACT grant is pinned separately over a controllable {@link TimeMeter}, where no drip can
     * intrude.
     */
    private static void assertAtFirstContactGrant(int allowed, String because) {
        assertThat(allowed)
                .as(because)
                .isBetween(EXPECTED_INITIAL_TOKENS, EXPECTED_INITIAL_TOKENS + 1);
    }

    @Test
    @DisplayName("should_return429_when_settlementSearchGetExceedsTheFirstContactBurst")
    void should_return429_when_settlementSearchGetExceedsTheFirstContactBurst() throws Exception {
        AuthRateLimitFilter filter = realFilter();

        int allowed = countAllowedBefore429(filter, get(SETTLEMENTS_PATH));

        assertAtFirstContactGrant(allowed,
                "a never-seen IP must NOT arrive holding the whole %d-token minute. Bucket4j "
                        .formatted(EXPECTED_CAPACITY)
                        + "initialises a bandwidth full unless told otherwise, and 240 requests of "
                        + "~10 ms is ~2.4 s of database work deliverable in one breath from a free, "
                        + "rotatable source address");
    }

    @Test
    @DisplayName("should_notForwardThrottledRequest_when_settlementSearchGetIsOverCap")
    void should_notForwardThrottledRequest_when_settlementSearchGetIsOverCap() throws Exception {
        AuthRateLimitFilter filter = realFilter();

        MockHttpServletResponse lastResponse = null;
        MockFilterChain lastChain = null;
        for (int i = 0; i < REQUESTS_TO_FIRE; i++) {
            lastResponse = new MockHttpServletResponse();
            lastChain = new MockFilterChain();
            filter.doFilterInternal(get(SETTLEMENTS_PATH), lastResponse, lastChain);
            if (lastResponse.getStatus() == 429) {
                break;
            }
        }

        assertThat(lastResponse.getStatus()).isEqualTo(429);
        assertThat(lastChain.getRequest())
                .as("a throttled settlement lookup must NOT reach the controller — forwarding it "
                        + "would still cost the database statement the cap exists to bound")
                .isNull();
    }

    @Test
    @DisplayName("should_setRetryAfterHeader_when_settlementSearchGetThrottled")
    void should_setRetryAfterHeader_when_settlementSearchGetThrottled() throws Exception {
        AuthRateLimitFilter filter = realFilter();

        MockHttpServletResponse lastResponse = null;
        for (int i = 0; i < REQUESTS_TO_FIRE; i++) {
            lastResponse = new MockHttpServletResponse();
            filter.doFilterInternal(get(SETTLEMENTS_PATH), lastResponse, new MockFilterChain());
            if (lastResponse.getStatus() == 429) {
                break;
            }
        }

        assertThat(lastResponse.getStatus()).isEqualTo(429);
        assertThat(lastResponse.getHeader("Retry-After"))
                .as("the autocomplete fires on every settled keystroke, so a throttled client that "
                        + "cannot see when to retry will simply keep hammering")
                .isNotNull();
        assertThat(lastResponse.getContentType())
                .as("the 429 body is the JSON too-many-requests envelope")
                .contains("application/json");
    }

    @Test
    @DisplayName("should_chargeExactlyOneToken_when_requestCarriesAPageParameter")
    void should_chargeExactlyOneToken_when_requestCarriesAPageParameter() throws Exception {
        // /settlements has no paging and therefore no token-cost function — unlike /search/**,
        // whose deep pages cost 2. Pinned because the two branches sit next to each other in
        // doFilterInternal and share a capacity constant: a copy-paste of searchTokenCost into
        // this branch would silently halve the budget of anything sending a stray `page`.
        MockHttpServletRequest request = get(SETTLEMENTS_PATH);
        request.setParameter("page", "7");

        assertAtFirstContactGrant(countAllowedBefore429(realFilter(), request),
                "every settlement request costs exactly 1 token, page parameter or not — a "
                        + "2-token charge would halve the observed allowance");
    }

    @Test
    @DisplayName("should_return429_when_theSameCacheableQueryIsRepeatedFromAFreshIp")
    void should_return429_when_theSameCacheableQueryIsRepeatedFromAFreshIp() throws Exception {
        // Phase 329 put a result cache behind this endpoint, so a repeat of «льв» costs the
        // database nothing. The bucket must still charge it: the filter runs BEFORE the controller
        // (and therefore before the cache), so a cache hit is indistinguishable from a miss here.
        // Moving the throttle after the controller, or exempting cache hits, reddens this.
        AuthRateLimitFilter filter = realFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", SETTLEMENTS_PATH);
        request.setRemoteAddr("10.0.0.78");
        request.setParameter("query", "льв");

        int allowed = countAllowedBefore429(filter, request);
        MockHttpServletResponse overCap = new MockHttpServletResponse();
        MockFilterChain overCapChain = new MockFilterChain();
        filter.doFilterInternal(request, overCap, overCapChain);

        assertAtFirstContactGrant(allowed,
                "the 61st identical query from a fresh IP must be 429 — a cacheable repeat spends "
                        + "a token exactly like a miss");
        assertThat(overCap.getStatus()).isEqualTo(429);
        assertThat(overCapChain.getRequest())
                .as("the throttled repeat must never reach the controller, and so never the cache")
                .isNull();
    }

    // ── bucket isolation ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("should_keepASeparateBudget_when_theSearchBucketIsAlreadyExhausted")
    void should_keepASeparateBudget_when_theSearchBucketIsAlreadyExhausted() throws Exception {
        // The sizing note's whole argument for a second bucket: a long browsing session on
        // /search/** must not 429 an unrelated registration from the same CGNAT egress. If the two
        // were ever folded into one cache this is the assertion that goes red.
        AuthRateLimitFilter filter = realFilter();
        countAllowedBefore429(filter, get(SEARCH_PATH));

        int settlementAllowed = countAllowedBefore429(filter, get(SETTLEMENTS_PATH));

        assertAtFirstContactGrant(settlementAllowed,
                "draining the discovery-search budget must leave the settlement budget intact");
    }

    @Test
    @DisplayName("should_passThrough_when_pathIsUnderSettlementsButNotTheExactRoute")
    void should_passThrough_when_pathIsUnderSettlementsButNotTheExactRoute() throws Exception {
        // The branch matches EXACTLY, not by prefix — there is one route and no /settlements/**
        // subtree. Asserted so a future `startsWith` "tidy-up" cannot silently start throttling
        // routes that were never sized for this budget.
        AuthRateLimitFilter filter = realFilter();

        MockHttpServletResponse response = null;
        MockFilterChain chain = null;
        for (int i = 0; i < REQUESTS_TO_FIRE; i++) {
            response = new MockHttpServletResponse();
            chain = new MockFilterChain();
            filter.doFilterInternal(get(SETTLEMENTS_PATH + "/00000000-0000-0000-0000-000000000000"),
                    response, chain);
        }

        assertThat(response.getStatus()).isNotEqualTo(429);
        assertThat(chain.getRequest()).isNotNull();
    }

    // ── the refill strategy (LOW item 5) ──────────────────────────────────────────────────────

    /** A {@link TimeMeter} the test advances by hand — the alternative to a banned sleep. */
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
    @DisplayName("should_declareGreedyRefill_when_theSettlementBandwidthIsBuilt")
    void should_declareGreedyRefill_when_theSettlementBandwidthIsBuilt() {
        Bandwidth bandwidth = AuthRateLimitFilter.settlementSearchBandwidth();

        assertThat(bandwidth.isGready())
                .as("a step refill hands the whole 240 back at the window boundary, which is what "
                        + "made one IP able to concentrate a minute's budget into a 6.02 s burst "
                        + "and saturate the pool. Greedy is the property under test")
                .isTrue();
        assertThat(bandwidth.isRefillIntervally()).isFalse();
        assertThat(bandwidth.getCapacity())
                .as("smoothing the refill must not change the sustained rate")
                .isEqualTo(EXPECTED_CAPACITY);
        assertThat(bandwidth.getRefillPeriodNanos()).isEqualTo(EXPECTED_WINDOW.toNanos());
    }

    @Test
    @DisplayName("should_notStartFull_when_anIpIsSeenForTheFirstTime")
    void should_notStartFull_when_anIpIsSeenForTheFirstTime() {
        // The greedy refill governs the SECOND budget onward and nothing else: bucket4j initialises
        // a bandwidth at full capacity, so before initialTokens() this bucket handed a never-seen IP
        // all 240 tokens at once — ~2.4 s of database work deliverable in one breath at pool-limited
        // concurrency, from an address that costs an attacker nothing to rotate. Asserted on a fresh
        // bucket over a STOPPED clock so not one drip token can mask the starting balance.
        LocalBucket bucket = Bucket.builder()
                .addLimit(AuthRateLimitFilter.settlementSearchBandwidth())
                .withCustomTimePrecision(new ControllableTime())
                .build();

        assertThat(bucket.getAvailableTokens())
                .as("a first-contact bucket must start at the documented %d-token grant, not at the "
                        + "%d-token sustained ceiling", EXPECTED_INITIAL_TOKENS, EXPECTED_CAPACITY)
                .isEqualTo(EXPECTED_INITIAL_TOKENS)
                .isLessThan(EXPECTED_CAPACITY);
    }

    @Test
    @DisplayName("should_reachTheFullCeiling_when_aWindowHasPassedSinceFirstContact")
    void should_reachTheFullCeiling_when_aWindowHasPassedSinceFirstContact() {
        // The other half of the contract: bounding the COLD START must not bound the sustained rate.
        // A legitimate client that has been around for a minute still has the documented 240/min.
        ControllableTime time = new ControllableTime();
        LocalBucket bucket = Bucket.builder()
                .addLimit(AuthRateLimitFilter.settlementSearchBandwidth())
                .withCustomTimePrecision(time)
                .build();

        time.advance(EXPECTED_WINDOW);

        assertThat(bucket.getAvailableTokens())
                .as("initialTokens caps the first burst only — the sizing decision recorded on "
                        + "SETTLEMENT_SEARCH_CAPACITY is unchanged")
                .isEqualTo(EXPECTED_CAPACITY);
    }

    @Test
    @DisplayName("should_dripTokensAcrossTheWindow_when_theBucketHasBeenDrained")
    void should_dripTokensAcrossTheWindow_when_theBucketHasBeenDrained() {
        ControllableTime time = new ControllableTime();
        LocalBucket bucket = Bucket.builder()
                .addLimit(AuthRateLimitFilter.settlementSearchBandwidth())
                .withCustomTimePrecision(time)
                .build();
        bucket.tryConsumeAsMuchAsPossible();

        // One capacity-share of the window — 60 s / 240 — must return exactly one token. Under a
        // step refill this is still 0, and stays 0 for the rest of the minute.
        time.advance(EXPECTED_WINDOW.dividedBy(EXPECTED_CAPACITY));

        assertThat(bucket.getAvailableTokens())
                .as("greedy refill drips ~1 token every %d ms; a step refill would return 0 here "
                        + "and then 240 at once",
                        EXPECTED_WINDOW.dividedBy(EXPECTED_CAPACITY).toMillis())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("should_returnHalfTheBudget_when_halfTheWindowHasElapsed")
    void should_returnHalfTheBudget_when_halfTheWindowHasElapsed() {
        ControllableTime time = new ControllableTime();
        LocalBucket bucket = Bucket.builder()
                .addLimit(AuthRateLimitFilter.settlementSearchBandwidth())
                .withCustomTimePrecision(time)
                .build();
        bucket.tryConsumeAsMuchAsPossible();

        time.advance(EXPECTED_WINDOW.dividedBy(2));

        assertThat(bucket.getAvailableTokens())
                .as("the budget is spread over the window it was sized for, not released in one "
                        + "step — this is the whole point of the change, and the assertion a "
                        + "revert to refillIntervally fails")
                .isEqualTo(EXPECTED_CAPACITY / 2);
    }

    @Test
    @DisplayName("should_capAtTheFullBudget_when_awholeWindowHasElapsed")
    void should_capAtTheFullBudget_when_awholeWindowHasElapsed() {
        ControllableTime time = new ControllableTime();
        LocalBucket bucket = Bucket.builder()
                .addLimit(AuthRateLimitFilter.settlementSearchBandwidth())
                .withCustomTimePrecision(time)
                .build();
        bucket.tryConsumeAsMuchAsPossible();

        time.advance(EXPECTED_WINDOW.multipliedBy(3));

        assertThat(bucket.getAvailableTokens())
                .as("greedy refill still saturates at the capacity — legitimate users get the same "
                        + "240/min they had before")
                .isEqualTo(EXPECTED_CAPACITY);
    }
}
