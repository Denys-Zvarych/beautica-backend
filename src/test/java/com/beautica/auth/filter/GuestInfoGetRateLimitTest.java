package com.beautica.auth.filter;

import com.beautica.config.BookingInfoWorkerProperties;
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
import java.util.List;
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

    /** ≥ 32 chars (35), built at runtime so no secret-looking literal sits in source. */
    private static final String WORKER_KEY = "wk-test".repeat(5);
    private static final String WORKER_EGRESS_ADDR = "203.0.113.7";
    private static final String VISITOR_A = "198.51.100.10";
    private static final String VISITOR_B = "198.51.100.11";

    /** The Spring constructor with the website-Worker key configured (feature on). */
    private AuthRateLimitFilter workerAwareFilter() {
        return workerAwareFilter(WORKER_KEY);
    }

    /** The Spring constructor with {@code configuredKey} bound as the website-Worker key. */
    private AuthRateLimitFilter workerAwareFilter(String configuredKey) {
        var props = new BookingInfoWorkerProperties();
        props.setWorkerKey(configuredKey);
        return new AuthRateLimitFilter(
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), props);
    }

    /** A Worker-shaped request: Worker egress IP as the socket peer, optional key + visitor headers. */
    private static MockHttpServletRequest workerGet(String path, String workerKey, String cfConnectingIp) {
        var req = get(path, WORKER_EGRESS_ADDR);
        if (workerKey != null) {
            req.addHeader("X-Beautica-Worker-Key", workerKey);
        }
        if (cfConnectingIp != null) {
            req.addHeader("CF-Connecting-IP", cfConnectingIp);
        }
        return req;
    }

    /** Fires {@link #REQUESTS_TO_FIRE} Worker-shaped requests (all from the Worker egress IP). */
    private static void exhaustViaWorker(AuthRateLimitFilter filter, String workerKey, String cfConnectingIp)
            throws Exception {
        for (int i = 0; i < REQUESTS_TO_FIRE; i++) {
            filter.doFilterInternal(workerGet(SALON_INFO_PATH, workerKey, cfConnectingIp),
                    new MockHttpServletResponse(), new MockFilterChain());
        }
    }

    private static MockHttpServletResponse fireOnce(AuthRateLimitFilter filter, MockHttpServletRequest req)
            throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilterInternal(req, response, new MockFilterChain());
        return response;
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

    // ── phase 383/384: the /reviews routes draw from the SAME page-info bucket ─────────────────

    private static final String MASTER_REVIEWS_PATH = "/api/v1/book/marija-l-cd34/reviews";
    private static final String SALON_REVIEWS_PATH = "/api/v1/book/s/salon-krasa-ab12/reviews";

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {MASTER_REVIEWS_PATH, SALON_REVIEWS_PATH})
    @DisplayName("GET reviews read past the 60/60 s per-IP budget → 429 + Retry-After, chain not reached")
    void should_return429_when_bookReviewsExceedsPerIpBudget(String path) throws Exception {
        AuthRateLimitFilter filter = realFilter();
        MockFilterChain[] chain = new MockFilterChain[1];

        MockHttpServletResponse throttled = floodUntilThrottled(filter, path, chain);

        assertThat(throttled.getStatus()).as("%s must be IP-throttled", path).isEqualTo(429);
        assertThat(throttled.getHeader("Retry-After")).isNotNull();
        assertThat(chain[0].getRequest()).isNull();
    }

    @Test
    @DisplayName("info and reviews reads from one IP draw from ONE shared budget (the 61st request, in either order, is 429)")
    void should_shareOneBudget_when_infoAndReviewsReadFromOneIp() throws Exception {
        AuthRateLimitFilter filter = realFilter();
        for (int i = 0; i < 30; i++) {
            fireOnce(filter, get(MASTER_INFO_PATH, REMOTE_ADDR));
            fireOnce(filter, get(MASTER_REVIEWS_PATH, REMOTE_ADDR));
        }

        MockHttpServletResponse sixtyFirst = fireOnce(filter, get(SALON_REVIEWS_PATH, REMOTE_ADDR));

        assertThat(sixtyFirst.getStatus()).isEqualTo(429);
    }

    @Test
    @DisplayName("reviews read with a valid worker key + CF-Connecting-IP is keyed on the forwarded visitor IP")
    void should_chargeVisitorBucket_when_reviewsReadCarriesValidWorkerKey() throws Exception {
        AuthRateLimitFilter filter = workerAwareFilter();
        for (int i = 0; i < REQUESTS_TO_FIRE; i++) {
            fireOnce(filter, workerGet(MASTER_REVIEWS_PATH, WORKER_KEY, VISITOR_A));
        }

        MockHttpServletResponse exhaustedVisitor = fireOnce(filter, workerGet(MASTER_REVIEWS_PATH, WORKER_KEY, VISITOR_A));
        MockHttpServletResponse otherVisitor = fireOnce(filter, workerGet(MASTER_REVIEWS_PATH, WORKER_KEY, VISITOR_B));

        assertThat(exhaustedVisitor.getStatus()).isEqualTo(429);
        assertThat(otherVisitor.getStatus()).as("a different visitor behind the same Worker egress is untouched")
                .isNotEqualTo(429);
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

    @Test
    @DisplayName("Worker secret present → the info bucket keys on CF-Connecting-IP, so one visitor's flood leaves another visitor (same egress IP) untouched")
    void should_keyOnForwardedClientIp_when_workerSecretPresent() throws Exception {
        AuthRateLimitFilter filter = workerAwareFilter();
        exhaustViaWorker(filter, WORKER_KEY, VISITOR_A);

        MockHttpServletResponse visitorA = fireOnce(filter, workerGet(SALON_INFO_PATH, WORKER_KEY, VISITOR_A));
        MockHttpServletResponse visitorB = fireOnce(filter, workerGet(MASTER_INFO_PATH, WORKER_KEY, VISITOR_B));

        assertThat(visitorA.getStatus()).as("the flooding visitor is throttled on its own bucket").isEqualTo(429);
        assertThat(visitorB.getStatus()).as("another visitor behind the same Worker egress IP is not")
                .isNotEqualTo(429);
    }

    @Test
    @DisplayName("No Worker secret → requests from one egress IP share one bucket and 429 past the cap, whatever CF-Connecting-IP says")
    void should_429_when_noSecret_and_sameEgressIpExceedsCap() throws Exception {
        AuthRateLimitFilter filter = workerAwareFilter();
        exhaustViaWorker(filter, null, VISITOR_A);

        MockHttpServletResponse otherVisitor = fireOnce(filter, workerGet(SALON_INFO_PATH, null, VISITOR_B));

        assertThat(otherVisitor.getStatus()).isEqualTo(429);
    }

    @Test
    @DisplayName("Wrong Worker secret → treated exactly like no secret (egress-IP keyed)")
    void should_keyOnEgressIp_when_workerSecretIsWrong() throws Exception {
        AuthRateLimitFilter filter = workerAwareFilter();
        exhaustViaWorker(filter, WORKER_KEY + "x", VISITOR_A);

        MockHttpServletResponse otherVisitor = fireOnce(filter, workerGet(SALON_INFO_PATH, "wrong", VISITOR_B));
        MockHttpServletResponse plainEgress = fireOnce(filter, workerGet(SALON_INFO_PATH, null, null));

        assertThat(otherVisitor.getStatus()).as("a forged CF-Connecting-IP must not mint a fresh bucket")
                .isEqualTo(429);
        assertThat(plainEgress.getStatus()).as("the wrong-key flood was charged to the egress IP bucket")
                .isEqualTo(429);
    }

    @ParameterizedTest(name = "CF-Connecting-IP=\"{0}\"")
    @ValueSource(strings = {"", "  ", "not-an-ip", "999.1.1.1", "1.2.3", "example.com", "1.2.3.4, 5.6.7.8",
            "::1::2::3", "fe80::1%eth0", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa:1",
            ".:", "..::1", ".1:2"})
    @DisplayName("Valid Worker secret but malformed CF-Connecting-IP → falls back to the egress-IP key")
    void should_fallBackToEgressIpKey_when_cfConnectingIpMalformed(String malformed) throws Exception {
        AuthRateLimitFilter filter = workerAwareFilter();
        exhaustViaWorker(filter, null, null);

        MockHttpServletResponse response = fireOnce(filter, workerGet(SALON_INFO_PATH, WORKER_KEY, malformed));

        assertThat(response.getStatus()).as("malformed CF-Connecting-IP must land on the exhausted egress bucket")
                .isEqualTo(429);
    }

    @Test
    @DisplayName("Valid Worker secret but no CF-Connecting-IP header → falls back to the egress-IP key")
    void should_fallBackToEgressIpKey_when_cfConnectingIpMissing() throws Exception {
        AuthRateLimitFilter filter = workerAwareFilter();
        exhaustViaWorker(filter, null, null);

        MockHttpServletResponse response = fireOnce(filter, workerGet(SALON_INFO_PATH, WORKER_KEY, null));

        assertThat(response.getStatus()).isEqualTo(429);
    }

    @Test
    @DisplayName("Feature off (public constructor, no key configured) → the Worker header is ignored")
    void should_ignoreWorkerHeader_when_featureOff() throws Exception {
        AuthRateLimitFilter filter = realFilter();
        exhaustViaWorker(filter, WORKER_KEY, VISITOR_A);

        MockHttpServletResponse otherVisitor = fireOnce(filter, workerGet(SALON_INFO_PATH, WORKER_KEY, VISITOR_B));

        assertThat(otherVisitor.getStatus()).isEqualTo(429);
    }

    @Test
    @DisplayName("Blank configured key → feature off: a blank header can never match")
    void should_ignoreWorkerHeader_when_configuredKeyBlank() throws Exception {
        var props = new BookingInfoWorkerProperties();
        props.setWorkerKey("   ");
        AuthRateLimitFilter filter = new AuthRateLimitFilter(
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), props);
        exhaustViaWorker(filter, "   ", VISITOR_A);

        MockHttpServletResponse otherVisitor = fireOnce(filter, workerGet(SALON_INFO_PATH, "   ", VISITOR_B));

        assertThat(otherVisitor.getStatus()).isEqualTo(429);
    }

    @Test
    @DisplayName("Configured key with a trailing newline (secret-store artefact) is stripped once: the trimmed header matches")
    void should_keyOnVisitorIp_when_configuredKeyHasTrailingNewline() throws Exception {
        AuthRateLimitFilter filter = workerAwareFilter(WORKER_KEY + "\n");
        exhaustViaWorker(filter, WORKER_KEY, VISITOR_A);

        MockHttpServletResponse otherVisitor = fireOnce(filter, workerGet(SALON_INFO_PATH, WORKER_KEY, VISITOR_B));

        assertThat(otherVisitor.getStatus()).as("matching uses the stripped key, so visitors keep separate buckets")
                .isNotEqualTo(429);
    }

    @Test
    @DisplayName("The Worker key applies to the info reads only: availability stays egress-IP keyed")
    void should_keepEgressIpKey_when_workerKeySentToAvailability() throws Exception {
        AuthRateLimitFilter filter = workerAwareFilter();
        for (int i = 0; i < REQUESTS_TO_FIRE; i++) {
            filter.doFilterInternal(workerGet(AVAILABILITY_PATH, WORKER_KEY, VISITOR_A),
                    new MockHttpServletResponse(), new MockFilterChain());
        }

        MockHttpServletResponse otherVisitor = fireOnce(filter, workerGet(AVAILABILITY_PATH, WORKER_KEY, VISITOR_B));

        assertThat(otherVisitor.getStatus()).isEqualTo(429);
    }

    @Test
    @DisplayName("Two spellings of one IPv6 visitor address share one bucket")
    void should_canonicaliseIpv6_when_keyingOnForwardedClientIp() {
        assertThat(AuthRateLimitFilter.visitorIpBucketKey("2001:db8:0:0:0:0:0:1"))
                .isEqualTo(AuthRateLimitFilter.visitorIpBucketKey("2001:DB8::1"))
                .isNotNull();
        assertThat(AuthRateLimitFilter.visitorIpBucketKey("::1.2.3.4"))
                .isEqualTo(AuthRateLimitFilter.visitorIpBucketKey("0:0:0:0:0:0:102:304"))
                .isNotNull();
        assertThat(AuthRateLimitFilter.visitorIpBucketKey("198.51.100.10")).isEqualTo("198.51.100.10");
    }

    @Test
    @DisplayName("Visitor bucket key: an all-zero /64 prefix (IPv4-compatible IPv6) keys on the full /128, not a shared ::/64")
    void should_keyOnFullAddress_when_ipv6Slash64PrefixIsAllZeros() {
        String compatA = AuthRateLimitFilter.visitorIpBucketKey("::1.2.3.4");
        String compatB = AuthRateLimitFilter.visitorIpBucketKey("::5.6.7.8");

        assertThat(compatA).isEqualTo("0:0:0:0:0:0:102:304");
        assertThat(compatB).isEqualTo("0:0:0:0:0:0:506:708");
        assertThat(AuthRateLimitFilter.visitorIpBucketKey("::1")).isEqualTo("0:0:0:0:0:0:0:1");
        assertThat(List.of(compatA, compatB)).doesNotContain("0:0:0:0:0:0:0:0/64");
    }

    @Test
    @DisplayName("Two IPv4-compatible IPv6 visitors keep separate info buckets")
    void should_keepSeparateBuckets_when_ipv6ZeroPrefixDifferentAddresses() throws Exception {
        AuthRateLimitFilter filter = workerAwareFilter();
        exhaustViaWorker(filter, WORKER_KEY, "::1.2.3.4");

        MockHttpServletResponse otherVisitor = fireOnce(filter, workerGet(SALON_INFO_PATH, WORKER_KEY, "::5.6.7.8"));

        assertThat(otherVisitor.getStatus()).as("a different zero-prefix address is not throttled").isNotEqualTo(429);
    }

    @Test
    @DisplayName("Two IPv6 visitors in one /64 share one info bucket: rotating the interface ID mints nothing")
    void should_shareBucket_when_ipv6SameSlash64() throws Exception {
        AuthRateLimitFilter filter = workerAwareFilter();
        exhaustViaWorker(filter, WORKER_KEY, "2001:db8:1:2::a");

        MockHttpServletResponse sameSlash64 =
                fireOnce(filter, workerGet(SALON_INFO_PATH, WORKER_KEY, "2001:db8:1:2:ffff:eeee:dddd:cccc"));

        assertThat(sameSlash64.getStatus()).as("an address in the exhausted /64 is throttled").isEqualTo(429);
    }

    @Test
    @DisplayName("IPv6 visitors in different /64s keep separate info buckets")
    void should_keepSeparateBuckets_when_ipv6DifferentSlash64() throws Exception {
        AuthRateLimitFilter filter = workerAwareFilter();
        exhaustViaWorker(filter, WORKER_KEY, "2001:db8:1:2::a");

        MockHttpServletResponse otherSlash64 =
                fireOnce(filter, workerGet(SALON_INFO_PATH, WORKER_KEY, "2001:db8:1:3::a"));

        assertThat(otherSlash64.getStatus()).as("a neighbouring /64 is not throttled").isNotEqualTo(429);
    }

    @Test
    @DisplayName("Visitor bucket key: IPv6 → /64 prefix; IPv4 and IPv4-mapped IPv6 → the plain IPv4 address")
    void should_keyIpv6OnSlash64AndIpv4Unchanged_when_derivingVisitorBucketKey() {
        assertThat(AuthRateLimitFilter.visitorIpBucketKey("2001:DB8:1:2:a:b:c:d"))
                .isEqualTo(AuthRateLimitFilter.visitorIpBucketKey("2001:db8:1:2::1"))
                .isEqualTo("2001:db8:1:2:0:0:0:0/64");
        assertThat(AuthRateLimitFilter.visitorIpBucketKey("198.51.100.10")).isEqualTo("198.51.100.10");
        assertThat(AuthRateLimitFilter.visitorIpBucketKey("::ffff:198.51.100.10")).isEqualTo("198.51.100.10");
        assertThat(AuthRateLimitFilter.visitorIpBucketKey(".1:2")).isNull();
    }
}
