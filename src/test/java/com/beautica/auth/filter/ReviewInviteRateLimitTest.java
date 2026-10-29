package com.beautica.auth.filter;

import com.beautica.config.BookingInfoWorkerProperties;
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
 * Phase 401 D1 — the guest review page's per-IP buckets: GET {@code /api/v1/public/review-invites/{token}} 30 / 10 min
 * (keyed on the Worker-forwarded visitor IP behind a valid worker key), the two POSTs ({@code /reviews},
 * {@code /opt-out}) 5 / 10 min on ONE shared bucket. Every injected bucket is permissive, so a 429 can only come from
 * the internally-built review-invite buckets.
 */
@DisplayName("AuthRateLimitFilter — guest review invite routes")
class ReviewInviteRateLimitTest {

    private static final String TOKEN = "AbCdEfGhIjKlMnOpQrStUv";
    private static final String GET_PATH = "/api/v1/public/review-invites/" + TOKEN;
    private static final String SUBMIT_PATH = GET_PATH + "/reviews";
    private static final String OPT_OUT_PATH = GET_PATH + "/opt-out";
    private static final String REMOTE_ADDR = "10.0.0.31";
    private static final String OTHER_ADDR = "10.0.0.32";
    private static final String WORKER_KEY = "wk-test".repeat(5);
    private static final String WORKER_EGRESS_ADDR = "203.0.113.7";

    private static LoadingCache<String, Bucket> permissive() {
        return Caffeine.newBuilder().build(key -> Bucket.builder().addLimit(unlimited()).build());
    }

    private static Bandwidth unlimited() {
        return BandwidthBuilder.builder()
                .capacity(1_000_000)
                .refillIntervally(1_000_000, Duration.ofMinutes(15))
                .build();
    }

    private static AuthRateLimitFilter filter() {
        var props = new BookingInfoWorkerProperties();
        props.setWorkerKey(WORKER_KEY);
        return new AuthRateLimitFilter(
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), props);
    }

    private static MockHttpServletRequest request(String method, String path, String remoteAddr) {
        var req = new MockHttpServletRequest(method, path);
        req.setRemoteAddr(remoteAddr);
        return req;
    }

    private static MockHttpServletRequest workerGet(String visitorIp) {
        var req = request("GET", GET_PATH, WORKER_EGRESS_ADDR);
        req.addHeader("X-Beautica-Worker-Key", WORKER_KEY);
        req.addHeader("X-Beautica-Client-IP", visitorIp);
        return req;
    }

    private static int fire(AuthRateLimitFilter filter, MockHttpServletRequest req) throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilterInternal(req, response, new MockFilterChain());
        return response.getStatus();
    }

    @Test
    @DisplayName("GET /review-invites/{token} — the 31st read from one IP within 10 min → 429")
    void should_throttleGet_when_thirtyFirstRequestFromOneIp() throws Exception {
        AuthRateLimitFilter filter = filter();
        for (int i = 0; i < 30; i++) {
            assertThat(fire(filter, request("GET", GET_PATH, REMOTE_ADDR))).isEqualTo(200);
        }

        var response = new MockHttpServletResponse();
        filter.doFilterInternal(request("GET", GET_PATH, REMOTE_ADDR), response, new MockFilterChain());
        int otherIp = fire(filter, request("GET", GET_PATH, OTHER_ADDR));

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isNotNull();
        assertThat(otherIp).isEqualTo(200);
    }

    @Test
    @DisplayName("POST /reviews and POST /opt-out share one 5-per-10-min bucket per IP")
    void should_shareOnePostBucket_when_submitAndOptOutFromOneIp() throws Exception {
        AuthRateLimitFilter filter = filter();
        for (int i = 0; i < 5; i++) {
            String path = i % 2 == 0 ? SUBMIT_PATH : OPT_OUT_PATH;
            assertThat(fire(filter, request("POST", path, REMOTE_ADDR))).isEqualTo(200);
        }

        int sixthSubmit = fire(filter, request("POST", SUBMIT_PATH, REMOTE_ADDR));
        int sixthOptOut = fire(filter, request("POST", OPT_OUT_PATH, REMOTE_ADDR));
        int getStillOpen = fire(filter, request("GET", GET_PATH, REMOTE_ADDR));

        assertThat(sixthSubmit).isEqualTo(429);
        assertThat(sixthOptOut).isEqualTo(429);
        assertThat(getStillOpen).isEqualTo(200);
    }

    @Test
    @DisplayName("GET keyed on X-Beautica-Client-IP when a valid Worker key accompanies it")
    void should_keyGetOnForwardedVisitorIp_when_workerKeyMatches() throws Exception {
        AuthRateLimitFilter filter = filter();
        for (int i = 0; i < 30; i++) {
            fire(filter, workerGet("198.51.100.10"));
        }

        int sameVisitor = fire(filter, workerGet("198.51.100.10"));
        int otherVisitor = fire(filter, workerGet("198.51.100.11"));

        assertThat(sameVisitor).isEqualTo(429);
        assertThat(otherVisitor).isEqualTo(200);
    }

    @Test
    @DisplayName("GET ignores X-Beautica-Client-IP without a Worker key (no spoofed rate-limit key)")
    void should_ignoreForwardedVisitorIp_when_workerKeyMissing() throws Exception {
        AuthRateLimitFilter filter = filter();
        for (int i = 0; i < 30; i++) {
            var spoofed = request("GET", GET_PATH, REMOTE_ADDR);
            spoofed.addHeader("X-Beautica-Client-IP", "198.51.100." + i);
            fire(filter, spoofed);
        }

        var next = request("GET", GET_PATH, REMOTE_ADDR);
        next.addHeader("X-Beautica-Client-IP", "198.51.100.200");
        int status = fire(filter, next);

        assertThat(status).isEqualTo(429);
    }

    @Test
    @DisplayName("POST keyed on the IPv6 /64 — rotating the interface id does not reset the bucket")
    void should_keyPostOnIpv6Slash64_when_clientRotatesInterfaceId() throws Exception {
        AuthRateLimitFilter filter = filter();
        for (int i = 1; i <= 5; i++) {
            assertThat(fire(filter, request("POST", SUBMIT_PATH, "2001:db8:1:2::" + Integer.toHexString(i))))
                    .isEqualTo(200);
        }

        int samePrefixNewAddress = fire(filter, request("POST", OPT_OUT_PATH, "2001:db8:1:2:ffff:ffff:ffff:ffff"));
        int otherPrefix = fire(filter, request("POST", SUBMIT_PATH, "2001:db8:1:3::1"));

        assertThat(samePrefixNewAddress).isEqualTo(429);
        assertThat(otherPrefix).isEqualTo(200);
    }

    @Test
    @DisplayName("IPv6 /64 keying leaves IPv4 and non-literal keys unchanged")
    void should_keepIpv4AndNonLiteralKeys_when_perIpv6PrefixApplied() {
        String ipv4 = AuthRateLimitFilter.perIpv6Prefix("198.51.100.10");
        String alreadyPrefixed = AuthRateLimitFilter.perIpv6Prefix("2001:db8:0:0:0:0:0:0/64");
        String ipv6 = AuthRateLimitFilter.perIpv6Prefix("2001:db8::abcd");

        assertThat(ipv4).isEqualTo("198.51.100.10");
        assertThat(alreadyPrefixed).isEqualTo("2001:db8:0:0:0:0:0:0/64");
        assertThat(ipv6).isEqualTo("2001:db8:0:0:0:0:0:0/64");
    }
}
