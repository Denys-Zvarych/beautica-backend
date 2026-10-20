package com.beautica.auth.filter;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BandwidthBuilder;
import io.github.bucket4j.Bucket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 377 audit (MEDIUM): the two public booking-page info reads — {@code GET /api/v1/book/{slug}/info}
 * (master page) and {@code GET /api/v1/book/s/{slug}/info} (salon page) — are {@code permitAll()} and were
 * unthrottled. Drives the REAL filter with every injected bucket permissive, so a 429 can only come from
 * the internally-built {@code guestInfoBuckets}.
 */
@DisplayName("AuthRateLimitFilter — GET /book/{slug}/info + /book/s/{slug}/info per-IP throttle")
class GuestInfoGetRateLimitTest {

    private static final String REMOTE_ADDR = "10.0.0.21";
    private static final String OTHER_ADDR = "10.0.0.22";
    private static final String MASTER_INFO_PATH = "/api/v1/book/marija-l-cd34/info";
    private static final String SALON_INFO_PATH = "/api/v1/book/s/salon-krasa-ab12/info";
    private static final String AVAILABILITY_PATH = "/api/v1/book/marija-l-cd34/availability";
    private static final String CATALOGUE_PATH = "/api/v1/masters/00000000-0000-0000-0000-000000000001/services";

    /** Well above the 60/60 s cap, so the bucket is exhausted with margin. */
    private static final int REQUESTS_TO_FIRE = 300;

    private static LoadingCache<String, Bucket> permissive() {
        return Caffeine.newBuilder().build(key -> Bucket.builder()
                .addLimit(unlimited())
                .build());
    }

    private static Bandwidth unlimited() {
        return BandwidthBuilder.builder()
                .capacity(1_000_000)
                .refillIntervally(1_000_000, Duration.ofMinutes(15))
                .build();
    }

    private AuthRateLimitFilter realFilter() {
        return new AuthRateLimitFilter(
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive());
    }

    private static MockHttpServletRequest get(String path, String remoteAddr) {
        return request("GET", path, remoteAddr);
    }

    private static MockHttpServletRequest request(String method, String path, String remoteAddr) {
        var req = new MockHttpServletRequest(method, path);
        req.setRemoteAddr(remoteAddr);
        return req;
    }

    private static void exhaust(AuthRateLimitFilter filter, String path) throws Exception {
        for (int i = 0; i < REQUESTS_TO_FIRE; i++) {
            filter.doFilterInternal(get(path, REMOTE_ADDR), new MockHttpServletResponse(), new MockFilterChain());
        }
    }

    /** Fires until the first 429 and returns that response (or the last one if none was throttled). */
    private static MockHttpServletResponse floodUntilThrottled(AuthRateLimitFilter filter, String path,
                                                               MockFilterChain[] chainOut) throws Exception {
        MockHttpServletResponse last = null;
        for (int i = 0; i < REQUESTS_TO_FIRE; i++) {
            last = new MockHttpServletResponse();
            chainOut[0] = new MockFilterChain();
            filter.doFilterInternal(get(path, REMOTE_ADDR), last, chainOut[0]);
            if (last.getStatus() == 429) {
                return last;
            }
        }
        return last;
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {MASTER_INFO_PATH, SALON_INFO_PATH})
    @DisplayName("GET info read past the 60/60 s per-IP budget → 429 + Retry-After, chain not reached")
    void should_return429_when_bookInfoExceedsPerIpBudget(String path) throws Exception {
        AuthRateLimitFilter filter = realFilter();
        MockFilterChain[] chain = new MockFilterChain[1];

        MockHttpServletResponse throttled = floodUntilThrottled(filter, path, chain);

        assertThat(throttled.getStatus()).as("%s must be IP-throttled", path).isEqualTo(429);
        assertThat(throttled.getHeader("Retry-After")).isNotNull();
        assertThat(chain[0].getRequest()).as("a throttled info read must not reach the chain").isNull();
    }

    @Test
    @DisplayName("throttled info read returns the same 429 body, content type and Retry-After as throttled availability")
    void should_returnIdentical429Response_when_infoAndAvailabilityAreThrottled() throws Exception {
        AuthRateLimitFilter filter = realFilter();
        MockFilterChain[] chain = new MockFilterChain[1];

        MockHttpServletResponse info = floodUntilThrottled(filter, SALON_INFO_PATH, chain);
        MockHttpServletResponse availability = floodUntilThrottled(filter, AVAILABILITY_PATH, chain);

        assertThat(info.getStatus()).isEqualTo(availability.getStatus()).isEqualTo(429);
        assertThat(info.getContentType()).isEqualTo(availability.getContentType());
        assertThat(info.getHeader("Retry-After")).isEqualTo(availability.getHeader("Retry-After"));
        assertThat(info.getContentAsString()).isEqualTo(availability.getContentAsString());
    }

    @Test
    @DisplayName("master page and salon page info reads from one IP draw from one shared budget")
    void should_shareOneBudget_when_masterAndSalonInfoReadFromOneIp() throws Exception {
        AuthRateLimitFilter filter = realFilter();
        exhaust(filter, MASTER_INFO_PATH);
        var response = new MockHttpServletResponse();

        filter.doFilterInternal(get(SALON_INFO_PATH, REMOTE_ADDR), response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(429);
    }

    @Test
    @DisplayName("an exhausted IP does not throttle a different IP")
    void should_throttlePerIp_when_oneSourceIsAlreadyExhausted() throws Exception {
        AuthRateLimitFilter filter = realFilter();
        exhaust(filter, SALON_INFO_PATH);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilterInternal(get(SALON_INFO_PATH, OTHER_ADDR), response, chain);

        assertThat(response.getStatus()).isNotEqualTo(429);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("exhausting the info budget leaves the same IP's availability and catalogue budgets untouched")
    void should_notConsumeOtherBuckets_when_infoBudgetIsExhausted() throws Exception {
        // Dedicated bucket: exhausting the info reads must leave the availability and catalogue budgets
        // of the same IP untouched (sharing would change those existing routes' behaviour).
        AuthRateLimitFilter filter = realFilter();
        exhaust(filter, MASTER_INFO_PATH);
        exhaust(filter, SALON_INFO_PATH);
        var availability = new MockHttpServletResponse();
        var catalogue = new MockHttpServletResponse();

        filter.doFilterInternal(get(AVAILABILITY_PATH, REMOTE_ADDR), availability, new MockFilterChain());
        filter.doFilterInternal(get(CATALOGUE_PATH, REMOTE_ADDR), catalogue, new MockFilterChain());

        assertThat(availability.getStatus()).isNotEqualTo(429);
        assertThat(catalogue.getStatus()).isNotEqualTo(429);
    }

    /**
     * Spellings Spring MVC routes to the same two info handlers, for BOTH routes. All but HEAD differ from the
     * canonical path only in the raw {@code getRequestURI()} — percent-encoded suffix/prefix, a matrix
     * parameter on the last segment, a doubled leading slash — which {@code resolveMatchPath} decodes /
     * strips / collapses. HEAD is routed to {@code @GetMapping} handlers, so it must hit the bucket too.
     */
    static Stream<Arguments> equivalentInfoSpellings() {
        return Stream.of(
                Arguments.of("HEAD", MASTER_INFO_PATH),
                Arguments.of("GET", "/api/v1/book/marija-l-cd34/inf%6f"),
                Arguments.of("GET", "/api/v1/book/marija-l-cd34/info;jsessionid=a"),
                Arguments.of("GET", "/api/v1/book/marija-l-cd34;jsessionid=a/info"),
                Arguments.of("GET", "//api/v1/book/marija-l-cd34/info"),
                Arguments.of("GET", "/api/v1/%62ook/marija-l-cd34/info"),
                Arguments.of("HEAD", SALON_INFO_PATH),
                Arguments.of("GET", "/api/v1/book/s/salon-krasa-ab12/inf%6f"),
                Arguments.of("GET", "/api/v1/book/s/salon-krasa-ab12/info;jsessionid=a"),
                Arguments.of("GET", "/api/v1/book/s/salon-krasa-ab12;jsessionid=a/info"),
                Arguments.of("GET", "//api/v1/book/s/salon-krasa-ab12/info"),
                Arguments.of("GET", "/api/v1/%62ook/s/salon-krasa-ab12/info"));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("equivalentInfoSpellings")
    @DisplayName("an equivalent spelling (HEAD, %-encoded, ;matrix, //) of an info read is throttled like the canonical GET")
    void should_return429_when_bookInfoReachedViaEquivalentSpelling(String method, String path) throws Exception {
        AuthRateLimitFilter filter = realFilter();
        MockHttpServletResponse last = null;
        MockFilterChain lastChain = null;

        for (int i = 0; i < REQUESTS_TO_FIRE && (last == null || last.getStatus() != 429); i++) {
            last = new MockHttpServletResponse();
            lastChain = new MockFilterChain();
            filter.doFilterInternal(request(method, path, REMOTE_ADDR), last, lastChain);
        }

        assertThat(last.getStatus()).as("%s %s must hit guestInfoBuckets and be throttled", method, path)
                .isEqualTo(429);
        assertThat(lastChain.getRequest()).as("a throttled %s %s must not reach the chain", method, path).isNull();
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("equivalentInfoSpellings")
    @DisplayName("an equivalent spelling draws from the SAME per-IP budget as the canonical GET (no second bucket)")
    void should_shareCanonicalBudget_when_equivalentSpellingUsed(String method, String path) throws Exception {
        AuthRateLimitFilter filter = realFilter();
        exhaust(filter, path.contains("/s/") ? SALON_INFO_PATH : MASTER_INFO_PATH);
        var response = new MockHttpServletResponse();

        filter.doFilterInternal(request(method, path, REMOTE_ADDR), response, new MockFilterChain());

        assertThat(response.getStatus()).as("%s %s after the canonical budget is spent", method, path)
                .isEqualTo(429);
    }
}
