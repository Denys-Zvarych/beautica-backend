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
 * <b>LOW-FIX REGRESSION NET (2026-09-02) — per-IP throttle on POST /api/v1/auth/invite/accept.</b>
 *
 * <p>{@code POST /api/v1/auth/invite/accept} is {@code permitAll()} — the invitee, not the
 * inviting {@code SALON_OWNER}/{@code SALON_ADMIN}, hits it directly from the emailed link. It
 * previously fell through every branch of {@link AuthRateLimitFilter#doFilterInternal} to the
 * unmatched-POST {@code else} clause with NO throttle at all, unlike its sibling
 * {@code POST /api/v1/auth/invite} (inviteBuckets). The token is a SHA-256-hashed 256-bit
 * {@code SecureRandom} value, so brute force is infeasible regardless of any rate limit — this
 * bucket bounds LOAD and unlimited-speed REPLAY of an already-leaked accept link, not guessing.
 *
 * <p>The production bucket ({@code inviteAcceptBuckets}, {@code RateLimitConfig}) is an injected
 * {@code @Qualifier} bean whose capacity is raised to 100 000 in {@code
 * src/test/resources/application-test.yml} so {@code InviteControllerIT} — which fires many real
 * HTTP calls against this exact path from 127.0.0.1 — is not itself throttled. This test
 * therefore builds the filter DIRECTLY against a hand-built, single-slot bucket at the matching
 * constructor position — bypassing that property entirely — so the raised test capacity cannot
 * neuter the throttling coverage. Same pattern as {@code ServiceWriteRateLimitRegressionTest}.
 */
@DisplayName("AuthRateLimitFilter — POST /auth/invite/accept per-IP throttle (LOW-fix regression net)")
class InviteAcceptPostRateLimitRegressionTest {

    private static final String REMOTE_ADDR = "10.0.0.88";
    private static final String OTHER_ADDR = "10.0.0.89";
    private static final String INVITE_ACCEPT_PATH = "/api/v1/auth/invite/accept";
    /** Capacity of the hand-built invite-accept bucket under test — deliberately tiny. */
    private static final long TEST_ACCEPT_CAPACITY = 3;

    private static LoadingCache<String, Bucket> permissive() {
        return Caffeine.newBuilder().build(key -> Bucket.builder()
                .addLimit(bandwidth(1_000_000))
                .build());
    }

    /** The bucket under test: 3 tokens / 15 min, so the 4th request in a burst must 429. */
    private static LoadingCache<String, Bucket> tinyInviteAcceptCache() {
        return Caffeine.newBuilder().build(key -> Bucket.builder()
                .addLimit(bandwidth(TEST_ACCEPT_CAPACITY))
                .build());
    }

    private static Bandwidth bandwidth(long capacity) {
        return BandwidthBuilder.builder()
                .capacity(capacity)
                .refillIntervally(capacity, Duration.ofMinutes(15))
                .build();
    }

    /**
     * 21 positional caches — one per {@code @Qualifier} arg on the production constructor. Only
     * the 21st (invite-accept, LAST) is constrained; every other bucket — including the 20th
     * (invite-validate) — is permissive, so a 429 here can only have come from the branch under
     * test.
     */
    private AuthRateLimitFilter filterWithTinyInviteAcceptBucket() {
        return new AuthRateLimitFilter(
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(),
                permissive(), tinyInviteAcceptCache());
    }

    private static MockHttpServletRequest postAccept(String remoteAddr) {
        var req = new MockHttpServletRequest("POST", INVITE_ACCEPT_PATH);
        req.setRemoteAddr(remoteAddr);
        return req;
    }

    @Test
    @DisplayName("should_return429_when_inviteAcceptPostExceedsPerIpCap")
    void should_return429_when_inviteAcceptPostExceedsPerIpCap() throws Exception {
        AuthRateLimitFilter filter = filterWithTinyInviteAcceptBucket();

        MockHttpServletResponse lastResponse = null;
        MockFilterChain lastChain = null;
        int allowedBeforeThrottle = 0;

        for (int i = 0; i < TEST_ACCEPT_CAPACITY + 5; i++) {
            lastResponse = new MockHttpServletResponse();
            lastChain = new MockFilterChain();
            filter.doFilterInternal(postAccept(REMOTE_ADDR), lastResponse, lastChain);
            if (lastResponse.getStatus() == 429) {
                break;
            }
            allowedBeforeThrottle++;
        }

        assertThat(allowedBeforeThrottle)
                .as("POST %s must consume the invite-accept bucket and 429 on request %d — an "
                        + "unthrottled route means the accept-invite flood guard is missing",
                        INVITE_ACCEPT_PATH, TEST_ACCEPT_CAPACITY + 1)
                .isEqualTo(TEST_ACCEPT_CAPACITY);
        assertThat(lastResponse.getStatus())
                .as("the throttled accept-invite request must return 429")
                .isEqualTo(429);
        assertThat(lastResponse.getHeader("Retry-After"))
                .as("a throttled accept-invite request must carry a Retry-After header reflecting "
                        + "the bucket's own 15-minute window so clients back off correctly")
                .isEqualTo("900");
        assertThat(lastResponse.getContentType())
                .as("the 429 body is the JSON too-many-requests envelope")
                .contains("application/json");
        assertThat(lastChain.getRequest())
                .as("the throttled accept-invite request must NOT be forwarded down the filter chain")
                .isNull();
    }

    @Test
    @DisplayName("should_notThrottle_when_requestsStayUnderTheCap")
    void should_notThrottle_when_requestsStayUnderTheCap() throws Exception {
        // Non-vacuity for the flood test above: legitimate traffic under the cap must sail
        // through untouched.
        AuthRateLimitFilter filter = filterWithTinyInviteAcceptBucket();

        for (int i = 0; i < TEST_ACCEPT_CAPACITY; i++) {
            var response = new MockHttpServletResponse();
            var chain = new MockFilterChain();

            filter.doFilterInternal(postAccept(REMOTE_ADDR), response, chain);

            assertThat(response.getStatus())
                    .as("request %d under the cap must pass", i + 1)
                    .isNotEqualTo(429);
            assertThat(chain.getRequest())
                    .as("request %d under the cap must be forwarded", i + 1)
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("should_throttlePerIp_when_oneSourceIsAlreadyExhausted")
    void should_throttlePerIp_when_oneSourceIsAlreadyExhausted() throws Exception {
        AuthRateLimitFilter filter = filterWithTinyInviteAcceptBucket();

        for (int i = 0; i < TEST_ACCEPT_CAPACITY + 5; i++) {
            filter.doFilterInternal(postAccept(REMOTE_ADDR),
                    new MockHttpServletResponse(), new MockFilterChain());
        }

        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        filter.doFilterInternal(postAccept(OTHER_ADDR), response, chain);

        assertThat(response.getStatus())
                .as("a second IP must keep its own budget after the first exhausted theirs")
                .isNotEqualTo(429);
        assertThat(chain.getRequest())
                .as("the second IP's request must be forwarded")
                .isNotNull();
    }
}
