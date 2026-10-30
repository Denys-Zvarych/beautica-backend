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
 * Review-trust phase 1 — per-IP throttle on {@code GET /api/v1/book/cancel/{token}} (the guest cancel
 * page's info read). It previously fell through the filter's unconditional non-POST bypass with no
 * throttle at all. The bucket ({@code cancelInfoBuckets}, 10 / 60 s per IP) is built inside the filter,
 * so the REAL production capacity is exercised here: every constructor-injected bucket is permissive and
 * any 429 can only come from the branch under test.
 */
@DisplayName("AuthRateLimitFilter — GET /book/cancel/{token} per-IP throttle")
class CancelInfoGetRateLimitTest {

    private static final String REMOTE_ADDR = "10.0.0.41";
    private static final String OTHER_ADDR = "10.0.0.42";
    private static final String CANCEL_PATH = "/api/v1/book/cancel/11111111-2222-3333-4444-555555555555";
    /** Production cap of the cancel-info bucket (CANCEL_INFO_CAPACITY). */
    private static final int CAPACITY = 10;

    private static LoadingCache<String, Bucket> permissive() {
        return Caffeine.newBuilder().build(key -> Bucket.builder().addLimit(unlimited()).build());
    }

    private static Bandwidth unlimited() {
        return BandwidthBuilder.builder()
                .capacity(1_000_000)
                .refillIntervally(1_000_000, Duration.ofMinutes(15))
                .build();
    }

    /** 25 permissive caches — one per {@code @Qualifier} bucket on the production constructor. */
    private static AuthRateLimitFilter realFilter() {
        return new AuthRateLimitFilter(
                permissive(), permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(), permissive());
    }

    private static MockHttpServletRequest request(String method, String path, String remoteAddr) {
        var req = new MockHttpServletRequest(method, path);
        req.setRemoteAddr(remoteAddr);
        return req;
    }

    private static void exhaust(AuthRateLimitFilter filter, String method, String remoteAddr) throws Exception {
        for (int i = 0; i < CAPACITY; i++) {
            filter.doFilterInternal(request(method, CANCEL_PATH, remoteAddr),
                    new MockHttpServletResponse(), new MockFilterChain());
        }
    }

    @Test
    @DisplayName("GET cancel info — 11th read from one IP in 60 s is 429 with Retry-After 60 and never reaches the controller")
    void should_return429WithRetryAfter_when_cancelInfoExceedsPerIpCap() throws Exception {
        AuthRateLimitFilter filter = realFilter();
        int allowed = 0;
        MockHttpServletResponse last = null;
        MockFilterChain lastChain = null;

        for (int i = 0; i < CAPACITY + 5; i++) {
            last = new MockHttpServletResponse();
            lastChain = new MockFilterChain();
            filter.doFilterInternal(request("GET", CANCEL_PATH, REMOTE_ADDR), last, lastChain);
            if (last.getStatus() == 429) {
                break;
            }
            allowed++;
        }

        assertThat(allowed).as("the first %d reads pass, the next one is throttled", CAPACITY).isEqualTo(CAPACITY);
        assertThat(last.getStatus()).isEqualTo(429);
        assertThat(last.getHeader("Retry-After")).isEqualTo("60");
        assertThat(last.getContentType()).contains("application/json");
        assertThat(lastChain.getRequest()).as("a throttled read never reaches the controller").isNull();
    }

    @Test
    @DisplayName("HEAD cancel info is throttled exactly like GET")
    void should_return429_when_cancelInfoHeadFloodedFromOneIp() throws Exception {
        AuthRateLimitFilter filter = realFilter();
        exhaust(filter, "HEAD", REMOTE_ADDR);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilterInternal(request("HEAD", CANCEL_PATH, REMOTE_ADDR), response, chain);

        assertThat(response.getStatus()).as("HEAD routes to the @GetMapping, so it is throttled like GET").isEqualTo(429);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    @DisplayName("one IP exhausting its read budget leaves another IP untouched")
    void should_keepSecondIpBudget_when_firstIpExhausted() throws Exception {
        AuthRateLimitFilter filter = realFilter();
        exhaust(filter, "GET", REMOTE_ADDR);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilterInternal(request("GET", CANCEL_PATH, OTHER_ADDR), response, chain);

        assertThat(response.getStatus()).isNotEqualTo(429);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("exhausted read budget never blocks the same IP from POSTing its cancel")
    void should_notSpendCancelPostBudget_when_cancelInfoExhausted() throws Exception {
        AuthRateLimitFilter filter = realFilter();
        exhaust(filter, "GET", REMOTE_ADDR);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilterInternal(request("POST", CANCEL_PATH, REMOTE_ADDR), response, chain);

        assertThat(response.getStatus())
                .as("the cancel POST has its own bucket — page reads must not lock the guest out of cancelling")
                .isNotEqualTo(429);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("unrelated GET under /book is not caught by the cancel-info bucket")
    void should_notThrottle_when_unrelatedBookGetRequestedManyTimes() throws Exception {
        AuthRateLimitFilter filter = realFilter();
        String unrelated = "/api/v1/book/marija-l-cd34/unrelated";

        for (int i = 0; i < CAPACITY + 10; i++) {
            var response = new MockHttpServletResponse();
            var chain = new MockFilterChain();

            filter.doFilterInternal(request("GET", unrelated, REMOTE_ADDR), response, chain);

            assertThat(response.getStatus()).as("request %d", i + 1).isNotEqualTo(429);
            assertThat(chain.getRequest()).isNotNull();
        }
    }
}
