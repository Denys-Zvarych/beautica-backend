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
 * bulk-harvest home addresses. {@link AuthRateLimitFilter} caps these GETs at
 * {@code SEARCH_CAPACITY=40 / 60 s} per IP via its internally-built {@code searchBuckets}.
 *
 * <p>This drives the <i>real</i> filter through {@code doFilterInternal} and asserts only
 * observable HTTP behaviour (the over-cap request returns 429 and is not forwarded), so it needs
 * no edit if the exact capacity is later tuned — it fires well above 40 to exhaust the bucket
 * regardless. The search bucket is built internally (like {@code otpVerifyBuckets}), so this test
 * references no new constructor arg; the 16 positional permissive caches mirror the public
 * 16-{@code @Qualifier} constructor.
 */
@DisplayName("AuthRateLimitFilter — GET /search/** per-IP throttle (SEC-fix address-scraping regression net)")
class SearchGetRateLimitRegressionTest {

    private static final String REMOTE_ADDR = "10.0.0.42";
    private static final String MASTERS_PATH = "/api/v1/search/masters";
    private static final String SALONS_PATH = "/api/v1/search/salons";

    /**
     * Fired well above the 40/min cap so the bucket is exhausted regardless of the exact capacity
     * the implementer keeps (40..50), keeping this test edit-free for that choice.
     */
    private static final int REQUESTS_TO_FIRE = 80;

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
        // 18 permissive caches — one positional arg per @Qualifier bucket on the production
        // constructor (now includes verifyPasswordResetOtpBuckets / changePasswordOtpBuckets,
        // Phase A5). The search-GET throttle is internal to the filter (built like
        // otpVerifyBuckets), so this test references no new constructor arg beyond the count
        // and asserts only on observable HTTP behaviour.
        return new AuthRateLimitFilter(
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive());
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
                .as("the cap must be the documented 40/min ceiling — the 41st request is the first throttled one")
                .isEqualTo(40);
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
