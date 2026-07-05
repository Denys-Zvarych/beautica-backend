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
 * <b>SEC-FIX REGRESSION NET — per-IP throttle on POST /api/v1/auth/logout.</b>
 *
 * <p>{@code POST /api/v1/auth/logout} was previously not covered by {@link AuthRateLimitFilter}
 * at all — an unbounded surface on an otherwise fully-throttled {@code /auth/*} namespace.
 * {@link AuthRateLimitFilter} now caps these POSTs at {@code LOGOUT_CAPACITY=30 / 60 s} per IP
 * via its internally-built {@code logoutBuckets} — deliberately generous (at/above the
 * {@code refreshBuckets} 20/min ceiling) since logout is the lowest-risk auth mutation in this
 * filter, while still bounding a pathological retry loop.
 *
 * <p>This drives the <i>real</i> filter through {@code doFilterInternal} and asserts only
 * observable HTTP behaviour. The logout bucket is built internally (like {@code inviteBuckets}
 * / {@code searchBuckets}), so this test references no new constructor arg; the 16 positional
 * permissive caches mirror the public 16-{@code @Qualifier} constructor.
 */
@DisplayName("AuthRateLimitFilter — POST /auth/logout per-IP throttle (SEC-fix regression net)")
class LogoutPostRateLimitRegressionTest {

    private static final String REMOTE_ADDR = "10.0.0.88";
    private static final String LOGOUT_PATH = "/api/v1/auth/logout";
    private static final int EXPECTED_CAP = 30;
    // Fired well above the 30/min cap so the bucket is exhausted regardless of the exact capacity.
    private static final int REQUESTS_TO_FIRE = 60;

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
        // 16 permissive caches — one positional arg per @Qualifier bucket on the production
        // constructor. The logout-POST throttle is internal to the filter (built like
        // inviteBuckets / otpVerifyBuckets), so this test references no new constructor arg.
        return new AuthRateLimitFilter(
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive());
    }

    private MockHttpServletRequest postLogout() {
        var req = new MockHttpServletRequest("POST", LOGOUT_PATH);
        req.setRemoteAddr(REMOTE_ADDR);
        return req;
    }

    @Test
    @DisplayName("should_return429_when_logoutPostExceedsPerIpCap")
    void should_return429_when_logoutPostExceedsPerIpCap() throws Exception {
        AuthRateLimitFilter filter = realFilter();

        MockHttpServletResponse lastResponse = null;
        MockFilterChain lastChain = null;
        boolean anyThrottled = false;
        int allowedBeforeThrottle = 0;

        for (int i = 0; i < REQUESTS_TO_FIRE; i++) {
            lastResponse = new MockHttpServletResponse();
            lastChain = new MockFilterChain();
            filter.doFilterInternal(postLogout(), lastResponse, lastChain);
            if (lastResponse.getStatus() == 429) {
                anyThrottled = true;
                break;
            }
            allowedBeforeThrottle++;
        }

        assertThat(anyThrottled)
                .as("POST %s must be IP-throttled — %d unthrottled requests from one IP means the "
                        + "logout throttle is missing", LOGOUT_PATH, REQUESTS_TO_FIRE)
                .isTrue();
        assertThat(allowedBeforeThrottle)
                .as("the cap must be the documented %d/min ceiling — request %d is the first throttled one",
                        EXPECTED_CAP, EXPECTED_CAP + 1)
                .isEqualTo(EXPECTED_CAP);
        assertThat(lastResponse.getStatus())
                .as("the throttled logout request must return 429")
                .isEqualTo(429);
        assertThat(lastChain.getRequest())
                .as("the throttled logout request must NOT be forwarded down the filter chain")
                .isNull();
    }

    @Test
    @DisplayName("should_setRetryAfterHeader_when_logoutPostThrottled")
    void should_setRetryAfterHeader_when_logoutPostThrottled() throws Exception {
        AuthRateLimitFilter filter = realFilter();

        MockHttpServletResponse lastResponse = null;
        for (int i = 0; i < REQUESTS_TO_FIRE; i++) {
            lastResponse = new MockHttpServletResponse();
            filter.doFilterInternal(postLogout(), lastResponse, new MockFilterChain());
            if (lastResponse.getStatus() == 429) {
                break;
            }
        }

        assertThat(lastResponse.getStatus()).isEqualTo(429);
        assertThat(lastResponse.getHeader("Retry-After"))
                .as("a throttled logout must carry a Retry-After header so clients back off")
                .isNotNull();
        assertThat(lastResponse.getContentType())
                .as("the 429 body is the JSON too-many-requests envelope")
                .contains("application/json");
    }
}
