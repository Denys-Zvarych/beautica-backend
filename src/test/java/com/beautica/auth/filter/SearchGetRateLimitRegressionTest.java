package com.beautica.auth.filter;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BandwidthBuilder;
import io.github.bucket4j.Bucket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>SEC-FIX REGRESSION NET — per-IP throttle on GET /api/v1/search/** (address-scraping guard).</b>
 *
 * <p>{@code GET /api/v1/search/masters} and {@code /api/v1/search/salons} are {@code permitAll()}
 * discovery reads that now surface authed-only street addresses for independent masters / salons.
 * Without an IP-layer throttle a single source IP could page through every district/city and
 * bulk-harvest home addresses, and unbounded DB amplification. {@link AuthRateLimitFilter} caps
 * these GETs at {@code SEARCH_CAPACITY} / 60 s per IP via its internally-built
 * {@code searchBuckets}.
 *
 * <p>This drives the <i>real</i> filter through {@code doFilterInternal} and asserts only
 * observable HTTP behaviour (the over-cap request returns 429 and is not forwarded). The exact
 * ceiling is asserted from {@link #EXPECTED_CAPACITY}, which is deliberately restated here rather
 * than read off the (private) production constant: the cap is a product decision about legitimate
 * client behaviour, so a silent change to it should fail this test and be re-argued. It was raised
 * from 40 to 240 on 2026-07-29 because an incremental search box issues ~2 requests per settled
 * keystroke and Ukrainian mobile users share a CGNAT source IP — see SEARCH_CAPACITY. The search bucket is built internally (like {@code otpVerifyBuckets}), so this test
 * references no new constructor arg; the 16 positional permissive caches mirror the public
 * 16-{@code @Qualifier} constructor.
 */
@DisplayName("AuthRateLimitFilter — GET /search/** per-IP throttle (SEC-fix address-scraping regression net)")
class SearchGetRateLimitRegressionTest {

    private static final String REMOTE_ADDR = "10.0.0.42";
    private static final String MASTERS_PATH = "/api/v1/search/masters";
    private static final String SALONS_PATH = "/api/v1/search/salons";

    /** The documented per-IP ceiling; the (EXPECTED_CAPACITY + 1)-th request is the first 429. */
    private static final int EXPECTED_CAPACITY = 240;

    /** Fired above the cap so the bucket is certain to be exhausted within the loop. */
    private static final int REQUESTS_TO_FIRE = EXPECTED_CAPACITY * 2;

    private static LoadingCache<String, Bucket> permissive() {
        return Caffeine.newBuilder().build(key -> Bucket.builder()
                .addLimit(unlimited())
                .build());
    }

    private static Bandwidth unlimited() {
        return BandwidthBuilder.builder()
                .capacity(1_000_000)
                .refillIntervally(1_000_000, Duration.ofMinutes(1))
                .build();
    }

    private AuthRateLimitFilter realFilter() {
        // 19 permissive caches — one positional arg per @Qualifier bucket on the production
        // constructor (now includes verifyPasswordResetOtpBuckets / changePasswordOtpBuckets,
        // Phase A5). The search-GET throttle is internal to the filter (built like
        // otpVerifyBuckets), so this test references no new constructor arg beyond the count
        // and asserts only on observable HTTP behaviour.
        return new AuthRateLimitFilter(
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive());
    }

    private MockHttpServletRequest getSearch(String path) {
        var req = new MockHttpServletRequest("GET", path);
        req.setRemoteAddr(REMOTE_ADDR);
        return req;
    }

    @Test
    @DisplayName("should_return429_when_searchMastersGetExceedsPerIpCap")
    void should_return429_when_searchMastersGetExceedsPerIpCap() throws Exception {
        AuthRateLimitFilter filter = realFilter();

        MockHttpServletResponse lastResponse = null;
        MockFilterChain lastChain = null;
        boolean anyThrottled = false;
        int allowedBeforeThrottle = 0;

        for (int i = 0; i < REQUESTS_TO_FIRE; i++) {
            lastResponse = new MockHttpServletResponse();
            lastChain = new MockFilterChain();
            filter.doFilterInternal(getSearch(MASTERS_PATH), lastResponse, lastChain);
            if (lastResponse.getStatus() == 429) {
                anyThrottled = true;
                break;
            }
            allowedBeforeThrottle++;
        }

        assertThat(anyThrottled)
                .as("GET %s must be IP-throttled — %d unthrottled requests from one IP means the "
                        + "address-scraping flood guard is missing", MASTERS_PATH, REQUESTS_TO_FIRE)
                .isTrue();
        assertThat(allowedBeforeThrottle)
                .as("the cap must be the documented %d/min ceiling — request %d is the first throttled one",
                        EXPECTED_CAPACITY, EXPECTED_CAPACITY + 1)
                .isEqualTo(EXPECTED_CAPACITY);
        assertThat(lastResponse.getStatus())
                .as("the throttled search request must return 429")
                .isEqualTo(429);
        assertThat(lastChain.getRequest())
                .as("the throttled search request must NOT be forwarded down the filter chain")
                .isNull();
    }

    @Test
    @DisplayName("should_return429_when_searchSalonsGetExceedsPerIpCap (same bucket covers the salons read)")
    void should_return429_when_searchSalonsGetExceedsPerIpCap() throws Exception {
        AuthRateLimitFilter filter = realFilter();

        MockHttpServletResponse lastResponse = null;
        MockFilterChain lastChain = null;
        boolean anyThrottled = false;

        for (int i = 0; i < REQUESTS_TO_FIRE; i++) {
            lastResponse = new MockHttpServletResponse();
            lastChain = new MockFilterChain();
            filter.doFilterInternal(getSearch(SALONS_PATH), lastResponse, lastChain);
            if (lastResponse.getStatus() == 429) {
                anyThrottled = true;
                break;
            }
        }

        assertThat(anyThrottled)
                .as("GET %s must consume the same per-IP search bucket (prefix /api/v1/search/)", SALONS_PATH)
                .isTrue();
        assertThat(lastResponse.getStatus()).isEqualTo(429);
        assertThat(lastChain.getRequest())
                .as("the throttled salons-search request must NOT be forwarded")
                .isNull();
    }

    // ── deep-page token surcharge (searchTokenCost) ────────────────────────────
    //
    // A request with offset > 0 can execute TWO statements (the data query plus the
    // out-of-range first-page total probe), and every page >= 5 is an unconditional
    // cache miss, so a caller sweeping page indices bought ~2x the DB work the
    // capacity was sized for. searchTokenCost charges 2 tokens for page > 0.
    //
    // This surcharge shipped with NO automated test — it was verified by curl only,
    // and the tests above exercise the 1-token path exclusively (they send no `page`
    // parameter at all). Both cost paths are pinned here, against the SAME restated
    // EXPECTED_CAPACITY, so a silent change to either the capacity or the cost
    // multiplier fails and must be re-argued.

    /** Tokens charged for {@code page=0} / absent page — see {@code searchTokenCost}. */
    private static final int FIRST_PAGE_TOKEN_COST = 1;

    /** Tokens charged for any {@code page > 0}. */
    private static final int DEEP_PAGE_TOKEN_COST = 2;

    @Test
    @DisplayName("should_allow240Requests_when_searchGetIsFirstPage (page=0 costs 1 token)")
    void should_allow240Requests_when_searchGetIsFirstPage() throws Exception {
        int allowed = countAllowedBefore429(realFilter(), MASTERS_PATH, "0");

        assertThat(allowed)
                .as("page=0 costs %d token, so the full %d-token bucket funds %d requests — "
                        + "request %d is the first 429",
                        FIRST_PAGE_TOKEN_COST, EXPECTED_CAPACITY,
                        EXPECTED_CAPACITY / FIRST_PAGE_TOKEN_COST,
                        EXPECTED_CAPACITY / FIRST_PAGE_TOKEN_COST + 1)
                .isEqualTo(EXPECTED_CAPACITY / FIRST_PAGE_TOKEN_COST);
    }

    @Test
    @DisplayName("should_allowOnlyHalfAsManyRequests_when_searchGetIsDeepPage (page>0 costs 2 tokens)")
    void should_allowOnlyHalfAsManyRequests_when_searchGetIsDeepPage() throws Exception {
        int allowed = countAllowedBefore429(realFilter(), MASTERS_PATH, "1");

        assertThat(allowed)
                .as("page=1 costs %d tokens, so the %d-token bucket funds only %d requests — "
                        + "request %d is the first 429",
                        DEEP_PAGE_TOKEN_COST, EXPECTED_CAPACITY,
                        EXPECTED_CAPACITY / DEEP_PAGE_TOKEN_COST,
                        EXPECTED_CAPACITY / DEEP_PAGE_TOKEN_COST + 1)
                .isEqualTo(EXPECTED_CAPACITY / DEEP_PAGE_TOKEN_COST);
    }

    @Test
    @DisplayName("should_chargeDeepPageExactlyTwiceFirstPage_when_comparingBothCostPaths")
    void should_chargeDeepPageExactlyTwiceFirstPage_when_comparingBothCostPaths() throws Exception {
        int firstPageAllowed = countAllowedBefore429(realFilter(), MASTERS_PATH, "0");
        int deepPageAllowed = countAllowedBefore429(realFilter(), MASTERS_PATH, "7");

        // Asserted as a RATIO rather than two absolutes so the property under test is
        // the surcharge itself, independent of the capacity value.
        assertThat(firstPageAllowed)
                .as("a deep page must cost exactly twice a first page — the surcharge is what makes "
                        + "SEARCH_CAPACITY mean the statements/s it was sized for")
                .isEqualTo(deepPageAllowed * DEEP_PAGE_TOKEN_COST);
    }

    @Test
    @DisplayName("should_chargeBaseCost_when_pageParamIsAbsentBlankNegativeOrUnparsable")
    void should_chargeBaseCost_when_pageParamIsAbsentBlankNegativeOrUnparsable() throws Exception {
        // An absent / blank / negative / unparsable page is NOT a deep page: malformed
        // paging is rejected by the DTO's own @PositiveOrZero / @Max / result-window
        // constraints, not by the throttle, so the filter charges the base cost.
        assertThat(countAllowedBefore429(realFilter(), MASTERS_PATH, null))
                .as("absent page → base cost")
                .isEqualTo(EXPECTED_CAPACITY);
        assertThat(countAllowedBefore429(realFilter(), MASTERS_PATH, "   "))
                .as("blank page → base cost")
                .isEqualTo(EXPECTED_CAPACITY);
        assertThat(countAllowedBefore429(realFilter(), MASTERS_PATH, "-3"))
                .as("negative page → base cost (page > 0 is the deep-page test)")
                .isEqualTo(EXPECTED_CAPACITY);
        assertThat(countAllowedBefore429(realFilter(), MASTERS_PATH, "not-a-number"))
                .as("unparsable page → base cost; the request will 400 in validation")
                .isEqualTo(EXPECTED_CAPACITY);
    }

    @Test
    @DisplayName("should_shareOneBucketAcrossCostPaths_when_deepPagesFollowFirstPages")
    void should_shareOneBucketAcrossCostPaths_when_deepPagesFollowFirstPages() throws Exception {
        AuthRateLimitFilter filter = realFilter();

        // Spend half the bucket on cheap first-page reads...
        int cheapRequests = EXPECTED_CAPACITY / 2;
        for (int i = 0; i < cheapRequests; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilterInternal(getSearch(MASTERS_PATH, "0"), response, new MockFilterChain());
            assertThat(response.getStatus())
                    .as("first-page request %d of %d must still be allowed", i + 1, cheapRequests)
                    .isNotEqualTo(429);
        }

        // ...then the SAME bucket must fund only a quarter as many deep pages.
        int remainingDeepPages = 0;
        while (true) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilterInternal(getSearch(MASTERS_PATH, "3"), response, new MockFilterChain());
            if (response.getStatus() == 429) {
                break;
            }
            remainingDeepPages++;
        }

        assertThat(remainingDeepPages)
                .as("one shared per-IP bucket: %d tokens remained after %d cheap reads, and each "
                        + "deep page costs %d", EXPECTED_CAPACITY - cheapRequests, cheapRequests,
                        DEEP_PAGE_TOKEN_COST)
                .isEqualTo((EXPECTED_CAPACITY - cheapRequests) / DEEP_PAGE_TOKEN_COST);
    }

    /**
     * Fires search GETs with the given {@code page} parameter until the first 429 and
     * returns how many were allowed. Bounded by {@link #REQUESTS_TO_FIRE} so a missing
     * throttle fails the caller's assertion instead of looping forever.
     */
    private int countAllowedBefore429(AuthRateLimitFilter filter, String path, String page)
            throws Exception {
        int allowed = 0;
        for (int i = 0; i < REQUESTS_TO_FIRE; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilterInternal(getSearch(path, page), response, new MockFilterChain());
            if (response.getStatus() == 429) {
                return allowed;
            }
            allowed++;
        }
        throw new AssertionError(
                "no 429 within " + REQUESTS_TO_FIRE + " requests — the search throttle is missing "
                        + "for path " + path + " with page=" + page);
    }

    /** {@link #getSearch(String)} plus an optional {@code page} query parameter. */
    private MockHttpServletRequest getSearch(String path, String page) {
        MockHttpServletRequest request = getSearch(path);
        if (page != null) {
            request.setParameter("page", page);
        }
        return request;
    }

    @Test
    @DisplayName("should_setRetryAfterHeader_when_searchGetThrottled")
    void should_setRetryAfterHeader_when_searchGetThrottled() throws Exception {
        AuthRateLimitFilter filter = realFilter();

        MockHttpServletResponse lastResponse = null;
        for (int i = 0; i < REQUESTS_TO_FIRE; i++) {
            lastResponse = new MockHttpServletResponse();
            filter.doFilterInternal(getSearch(MASTERS_PATH), lastResponse, new MockFilterChain());
            if (lastResponse.getStatus() == 429) {
                break;
            }
        }

        assertThat(lastResponse.getStatus()).isEqualTo(429);
        assertThat(lastResponse.getHeader("Retry-After"))
                .as("a throttled search read must carry a Retry-After header so clients back off")
                .isNotNull();
        assertThat(lastResponse.getContentType())
                .as("the 429 body is the JSON too-many-requests envelope")
                .contains("application/json");
    }

    @Test
    @DisplayName("should_passThrough_when_nonSearchGet (a GET outside /search/** is not search-throttled)")
    void should_passThrough_when_nonSearchGet() throws Exception {
        AuthRateLimitFilter filter = realFilter();

        // A GET on a different prefix must NOT consume the search bucket — guards against the
        // matcher over-broadening beyond /api/v1/search/**.
        var get = new MockHttpServletRequest("GET", "/api/v1/salons/some-id/portfolio");
        get.setRemoteAddr(REMOTE_ADDR);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        // Fire well above the search cap on the unrelated path — it must never be throttled.
        for (int i = 0; i < REQUESTS_TO_FIRE; i++) {
            response = new MockHttpServletResponse();
            chain = new MockFilterChain();
            filter.doFilterInternal(get, response, chain);
        }

        assertThat(response.getStatus())
                .as("a non-search GET must pass through (not 429) regardless of request count")
                .isNotEqualTo(429);
        assertThat(chain.getRequest())
                .as("a non-search GET must be forwarded down the filter chain")
                .isNotNull();
    }
}
