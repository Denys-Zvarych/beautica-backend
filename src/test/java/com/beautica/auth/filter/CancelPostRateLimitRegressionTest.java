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
 * <b>LOW-FIX REGRESSION NET (Phase 13.4 — uncapped guest cancel-by-link POST, IP layer).</b>
 *
 * <p>{@code POST /api/v1/book/cancel/{token}} is {@code permitAll()} in {@code SecurityConfig}
 * (the guest holds only the one-time cancel token — no Spring principal). With no IP-layer
 * throttle a single source IP can pour cancel attempts at the endpoint: each accepted POST
 * runs the slug/booking graph load + advisory-style conditional consume, and a successful
 * cancellation dispatches a billable cancellation SMS. This guard caps the cancel POST per
 * source IP, mirroring the {@code guestBookingBuckets} / {@code otpSendBuckets} defences
 * already in {@link AuthRateLimitFilter} (Phase 13.3 pattern).
 *
 * <p><b>EXPECTED-RED until backend-dev adds the {@code POST /api/v1/book/cancel/{token}} branch +
 * per-IP bucket to {@link AuthRateLimitFilter}.</b> It drives the <i>real</i> filter through
 * {@code doFilterInternal} and asserts only observable HTTP behaviour (a throttled request
 * returns 429 and is not forwarded), so it needs no edit once the bucket is wired internally —
 * the assertion flips from RED to GREEN. Against current code the cancel POST falls through the
 * {@code else} branch (forwarded, never throttled), so {@code anyThrottled} stays false (RED now).
 *
 * <p>The path uses a literal UUID token segment matching the production matcher
 * {@code /api/v1/book/cancel/{token}}; the security matcher wildcard matches exactly one path
 * segment, so a concrete token is required here. The cap value is intentionally NOT asserted —
 * the test fires well above any reasonable cap so the implementer's exact choice is free.
 */
@DisplayName("AuthRateLimitFilter — POST /book/cancel/{token} per-IP throttle (LOW-fix regression, expected-red until fix lands)")
class CancelPostRateLimitRegressionTest {

    private static final String REMOTE_ADDR = "10.0.0.9";
    private static final String CANCEL_PATH =
            "/api/v1/book/cancel/11111111-2222-3333-4444-555555555555";

    /**
     * Fired well above any reasonable cap so the bucket is exhausted regardless of the exact
     * capacity the implementer picks (3..30 / window), keeping this test edit-free for that choice.
     */
    private static final int REQUESTS_TO_FIRE = 60;

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
        // constructor. The cancel-POST throttle the fix adds is internal to the filter (built
        // like otpVerifyBuckets / guestBookingBuckets), so this test references no new constructor
        // arg: it asserts only observable HTTP behaviour and stays compatible once wired.
        return new AuthRateLimitFilter(
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive());
    }

    private MockHttpServletRequest postCancel() {
        var req = new MockHttpServletRequest("POST", CANCEL_PATH);
        req.setRemoteAddr(REMOTE_ADDR);
        return req;
    }

    @Test
    @DisplayName("should_return429_when_cancelPostExceedsPerIpCap")
    void should_return429_when_cancelPostExceedsPerIpCap() throws Exception {
        AuthRateLimitFilter filter = realFilter();

        MockHttpServletResponse lastResponse = null;
        MockFilterChain lastChain = null;
        boolean anyThrottled = false;

        for (int i = 0; i < REQUESTS_TO_FIRE; i++) {
            lastResponse = new MockHttpServletResponse();
            lastChain = new MockFilterChain();
            filter.doFilterInternal(postCancel(), lastResponse, lastChain);
            if (lastResponse.getStatus() == 429) {
                anyThrottled = true;
                break;
            }
        }

        assertThat(anyThrottled)
                .as("POST /book/cancel/{token} must be IP-throttled — %d unthrottled requests from "
                        + "one IP means the cancel-by-link flood guard is missing", REQUESTS_TO_FIRE)
                .isTrue();
        assertThat(lastResponse.getStatus())
                .as("the throttled cancel request must return 429")
                .isEqualTo(429);
        assertThat(lastChain.getRequest())
                .as("the throttled cancel request must NOT be forwarded down the filter chain")
                .isNull();
    }

    @Test
    @DisplayName("should_passThrough_when_cancelGetInfo (unauthenticated cancel-info reads are not POST-throttled)")
    void should_passThrough_when_cancelGetInfo() throws Exception {
        AuthRateLimitFilter filter = realFilter();

        // A GET on the same /book/cancel/{token} path must NOT consume the cancel-POST bucket —
        // guards against the fix over-broadening its matcher to all /book/cancel/** traffic.
        var get = new MockHttpServletRequest("GET", CANCEL_PATH);
        get.setRemoteAddr(REMOTE_ADDR);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilterInternal(get, response, chain);

        assertThat(response.getStatus())
                .as("GET cancel-info must pass through (not 429)")
                .isNotEqualTo(429);
        assertThat(chain.getRequest())
                .as("GET cancel-info must be forwarded down the filter chain")
                .isNotNull();
    }

    @Test
    @DisplayName("should_passThrough_when_unrelatedPath (cancel throttle must not catch other POSTs)")
    void should_passThrough_when_unrelatedPath() throws Exception {
        AuthRateLimitFilter filter = realFilter();

        // An unrelated POST on a different /book/** sub-path must NOT be caught by the cancel
        // bucket. Booking POST has its own (separate) bucket; here we use a path that is neither
        // booking nor cancel so it falls through unthrottled regardless of either guard.
        var post = new MockHttpServletRequest("POST", "/api/v1/book/marija-l-cd34/unrelated");
        post.setRemoteAddr(REMOTE_ADDR);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilterInternal(post, response, chain);

        assertThat(response.getStatus())
                .as("an unrelated /book POST must pass through (not 429)")
                .isNotEqualTo(429);
        assertThat(chain.getRequest())
                .as("an unrelated /book POST must be forwarded down the filter chain")
                .isNotNull();
    }
}
