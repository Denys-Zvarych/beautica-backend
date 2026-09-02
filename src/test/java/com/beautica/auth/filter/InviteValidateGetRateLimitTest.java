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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>LOW-FIX REGRESSION NET (2026-09-02) — per-IP throttle on GET /api/v1/auth/invite/validate.</b>
 *
 * <p>{@code GET /api/v1/auth/invite/validate} is {@code permitAll()} — the invitee hits it
 * directly from the emailed link, before {@code POST /api/v1/auth/invite/accept}. It previously
 * hit the filter's UNCONDITIONAL non-POST early return ({@code if (!HttpMethod.POST.matches
 * (method)) { filterChain.doFilter(...); return; }}) with NO throttle at all. The token is a
 * SHA-256-hashed 256-bit {@code SecureRandom} value, so brute force is infeasible regardless of
 * any rate limit here — this bucket bounds LOAD and unlimited-speed REPLAY of an already-leaked
 * link, not guessing.
 *
 * <p>The production bucket ({@code inviteValidateBuckets}, {@code RateLimitConfig}) is an
 * injected {@code @Qualifier} bean whose capacity is raised to 100 000 in {@code
 * src/test/resources/application-test.yml} so {@code InviteControllerIT} — which fires several
 * dozen real HTTP calls against this exact path from 127.0.0.1 — is not itself throttled. This
 * test therefore builds the filter DIRECTLY against a hand-built, single-slot bucket at the
 * matching constructor position — bypassing that property entirely — so the raised test capacity
 * cannot neuter the throttling coverage. Same pattern as {@code ServiceWriteRateLimitRegressionTest}.
 */
@DisplayName("AuthRateLimitFilter — GET /auth/invite/validate per-IP throttle (LOW-fix regression net)")
class InviteValidateGetRateLimitTest {

    private static final String REMOTE_ADDR = "10.0.0.99";
    private static final String OTHER_ADDR = "10.0.0.100";
    private static final String VALIDATE_PATH = "/api/v1/auth/invite/validate";
    /** Capacity of the hand-built invite-validate bucket under test — deliberately tiny. */
    private static final long TEST_VALIDATE_CAPACITY = 4;

    private static LoadingCache<String, Bucket> permissive() {
        return Caffeine.newBuilder().build(key -> Bucket.builder()
                .addLimit(bandwidth(1_000_000))
                .build());
    }

    /** The bucket under test: 4 tokens / minute, so the 5th request in a burst must 429. */
    private static LoadingCache<String, Bucket> tinyInviteValidateCache() {
        return Caffeine.newBuilder().build(key -> Bucket.builder()
                .addLimit(bandwidth(TEST_VALIDATE_CAPACITY))
                .build());
    }

    private static Bandwidth bandwidth(long capacity) {
        return BandwidthBuilder.builder()
                .capacity(capacity)
                .refillIntervally(capacity, Duration.ofMinutes(1))
                .build();
    }

    /**
     * 21 positional caches — one per {@code @Qualifier} arg on the production constructor. Only
     * the 20th (invite-validate) is constrained; every other bucket — including the 21st
     * (invite-accept) — is permissive, so a 429 here can only have come from the branch under
     * test.
     */
    private AuthRateLimitFilter filterWithTinyInviteValidateBucket() {
        return new AuthRateLimitFilter(
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(),
                tinyInviteValidateCache(), permissive());
    }

    private static MockHttpServletRequest get(String path, String remoteAddr) {
        var req = new MockHttpServletRequest("GET", path);
        req.setRemoteAddr(remoteAddr);
        return req;
    }

    @Test
    @DisplayName("should_return429_when_inviteValidateFloodedFromOneIp")
    void should_return429_when_inviteValidateFloodedFromOneIp() throws Exception {
        AuthRateLimitFilter filter = filterWithTinyInviteValidateBucket();

        MockHttpServletResponse lastResponse = null;
        MockFilterChain lastChain = null;
        int allowedBeforeThrottle = 0;

        for (int i = 0; i < TEST_VALIDATE_CAPACITY + 5; i++) {
            lastResponse = new MockHttpServletResponse();
            lastChain = new MockFilterChain();
            filter.doFilterInternal(get(VALIDATE_PATH, REMOTE_ADDR), lastResponse, lastChain);
            if (lastResponse.getStatus() == 429) {
                break;
            }
            allowedBeforeThrottle++;
        }

        assertThat(allowedBeforeThrottle)
                .as("GET %s must consume the invite-validate bucket and 429 on request %d — an "
                        + "unthrottled route means the flood guard is missing",
                        VALIDATE_PATH, TEST_VALIDATE_CAPACITY + 1)
                .isEqualTo(TEST_VALIDATE_CAPACITY);
        assertThat(lastResponse.getStatus())
                .as("the throttled invite-validate request must return 429")
                .isEqualTo(429);
        assertThat(lastResponse.getHeader("Retry-After"))
                .as("a throttled caller must be told when to come back")
                .isEqualTo("60");
        assertThat(lastResponse.getContentType())
                .as("the 429 body is the JSON too-many-requests envelope")
                .contains("application/json");
        assertThat(lastChain.getRequest())
                .as("the throttled invite-validate request must NOT be forwarded down the filter "
                        + "chain — previewInvite must never run for a throttled caller")
                .isNull();
    }

    @Test
    @DisplayName("should_notThrottle_when_requestsStayUnderTheCap")
    void should_notThrottle_when_requestsStayUnderTheCap() throws Exception {
        // Non-vacuity for the flood test above: legitimate traffic under the cap must sail
        // through untouched.
        AuthRateLimitFilter filter = filterWithTinyInviteValidateBucket();

        for (int i = 0; i < TEST_VALIDATE_CAPACITY; i++) {
            var response = new MockHttpServletResponse();
            var chain = new MockFilterChain();

            filter.doFilterInternal(get(VALIDATE_PATH, REMOTE_ADDR), response, chain);

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
        AuthRateLimitFilter filter = filterWithTinyInviteValidateBucket();

        for (int i = 0; i < TEST_VALIDATE_CAPACITY + 5; i++) {
            filter.doFilterInternal(get(VALIDATE_PATH, REMOTE_ADDR),
                    new MockHttpServletResponse(), new MockFilterChain());
        }

        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        filter.doFilterInternal(get(VALIDATE_PATH, OTHER_ADDR), response, chain);

        assertThat(response.getStatus())
                .as("a second IP must keep its own budget after the first exhausted theirs")
                .isNotEqualTo(429);
        assertThat(chain.getRequest())
                .as("the second IP's request must be forwarded")
                .isNotNull();
    }

    /**
     * <b>The regression pin for the early-return change.</b> Adding the invite-validate GET
     * branch means the filter's {@code if (!HttpMethod.POST.matches(method))} early return is no
     * longer the FIRST thing every GET hits — a new equality check runs ahead of it. This test
     * proves that check is scoped to the one literal path: an unrelated, un-bucketed GET (here,
     * a master-reviews read that matches no branch in the filter at all — not media, not
     * master-availability slots/working-days, not guest-availability, not search, not
     * invite-validate) must sail through completely unthrottled even when fired far past the
     * tiny invite-validate cap under test. A regression that widened the new check into a
     * path-prefix or a blanket GET match would fail this test with a 429.
     */
    @Test
    @DisplayName("should_notThrottle_when_unrelatedGetPathRequestedManyTimes")
    void should_notThrottle_when_unrelatedGetPathRequestedManyTimes() throws Exception {
        AuthRateLimitFilter filter = filterWithTinyInviteValidateBucket();
        String unrelatedPath = "/api/v1/masters/" + UUID.randomUUID() + "/reviews";

        for (int i = 0; i < TEST_VALIDATE_CAPACITY + 20; i++) {
            var response = new MockHttpServletResponse();
            var chain = new MockFilterChain();

            filter.doFilterInternal(get(unrelatedPath, REMOTE_ADDR), response, chain);

            assertThat(response.getStatus())
                    .as("request %d to an unrelated, un-bucketed GET path must never be throttled "
                            + "— the invite-validate check must not have widened the non-POST bypass",
                            i + 1)
                    .isNotEqualTo(429);
            assertThat(chain.getRequest())
                    .as("request %d to an unrelated GET path must be forwarded", i + 1)
                    .isNotNull();
        }
    }
}
