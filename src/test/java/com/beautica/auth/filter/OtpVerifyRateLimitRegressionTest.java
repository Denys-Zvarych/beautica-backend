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
 * <b>CRITICAL-FIX REGRESSION NET (Phase 13.2 — uncapped OTP verify brute-force, IP layer).</b>
 *
 * <p>The second half of the brute-force fix is an IP-layer Bucket4j throttle on
 * {@code POST /api/v1/book/otp/verify}, mirroring the existing {@code otpSendBuckets}
 * guard on {@code /send}. Without it, a single IP can keep requesting fresh OTPs (within
 * the /send cap) and pour verify attempts at each one. This guard caps verify attempts
 * per source IP so the per-OTP attempt counter cannot simply be reset by re-sending.
 *
 * <p><b>This test encodes the not-yet-applied fix and is EXPECTED-RED until backend-dev
 * adds the {@code /book/otp/verify} branch + per-IP bucket to {@link AuthRateLimitFilter}.</b>
 * It drives the <i>real</i> filter through {@code doFilterInternal} and asserts only on
 * observable HTTP behaviour (a throttled request returns 429 and is not forwarded), so it
 * needs no edit once the dev wires the new bucket internally — the assertion flips from
 * RED to GREEN. Against the current code every verify request passes through (200), so
 * the throttled-request assertion fails (expected RED now).
 *
 * <p><b>Conflict to resolve in the fix loop:</b> {@code AuthRateLimitFilterTest}
 * (inner class {@code OtpSendEndpoint}) contains {@code should_passThrough_when_postOtpVerify}
 * which asserts verify is NOT rate-limited. That test pins the <i>old</i> behaviour and
 * must be deleted/updated when this guard lands.
 */
@DisplayName("AuthRateLimitFilter — POST /book/otp/verify per-IP throttle (CRITICAL-fix regression, expected-red until fix lands)")
class OtpVerifyRateLimitRegressionTest {

    private static final String REMOTE_ADDR = "10.0.0.1";
    private static final String VERIFY_PATH = "/api/v1/book/otp/verify";

    /**
     * Number of requests fired from one IP. The verify cap should be modest (≈5 / 15 min,
     * matching the per-OTP attempt cap intent). Firing well above any reasonable cap
     * guarantees the bucket is exhausted regardless of the exact capacity the implementer
     * picks, so this test does not need editing for a 3..10 cap choice.
     */
    private static final int REQUESTS_TO_FIRE = 25;

    /** A real, always-permissive cache for every bucket the filter does not throttle here. */
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
        // 16 permissive caches — one positional arg per @Qualifier bucket on the production
        // constructor. The verify throttle the fix adds is internal to the filter, so this
        // test does not (and must not) reference any new constructor arg: it asserts only on
        // observable HTTP behaviour and stays compatible once the dev wires the new bucket.
        return new AuthRateLimitFilter(
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive());
    }

    private MockHttpServletRequest postVerify() {
        var req = new MockHttpServletRequest("POST", VERIFY_PATH);
        req.setRemoteAddr(REMOTE_ADDR);
        return req;
    }

    @Test
    @DisplayName("should_rateLimit_verifyEndpoint_perIp")
    void should_rateLimit_verifyEndpoint_perIp() throws Exception {
        AuthRateLimitFilter filter = realFilter();

        MockHttpServletResponse lastResponse = null;
        MockFilterChain lastChain = null;
        boolean anyThrottled = false;

        for (int i = 0; i < REQUESTS_TO_FIRE; i++) {
            lastResponse = new MockHttpServletResponse();
            lastChain = new MockFilterChain();
            filter.doFilterInternal(postVerify(), lastResponse, lastChain);
            if (lastResponse.getStatus() == 429) {
                anyThrottled = true;
                break;
            }
        }

        // The CRITICAL contract: a single IP cannot fire unbounded verify attempts. At
        // least one request within REQUESTS_TO_FIRE must be throttled (429) and not
        // forwarded down the chain.
        assertThat(anyThrottled)
                .as("POST /book/otp/verify must be IP-throttled — %d unthrottled requests from "
                        + "one IP means the verify brute-force guard is missing", REQUESTS_TO_FIRE)
                .isTrue();
        assertThat(lastResponse.getStatus())
                .as("the throttled verify request must return 429")
                .isEqualTo(429);
        assertThat(lastChain.getRequest())
                .as("the throttled verify request must NOT be forwarded down the filter chain")
                .isNull();
    }
}
