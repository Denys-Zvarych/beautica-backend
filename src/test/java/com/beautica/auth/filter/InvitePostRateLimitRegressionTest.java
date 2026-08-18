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
 * <b>SEC-FIX REGRESSION NET — per-IP throttle on POST /api/v1/auth/invite (timing/enumeration +
 * invite-email flood guard).</b>
 *
 * <p>{@code POST /api/v1/auth/invite} is the residual side-channel left after the
 * {@code InviteService} 409-&gt;idempotent fix: the already-registered and active-invite branches
 * do measurably less work than the brand-new branch, so without an IP-layer throttle an
 * authenticated {@code SALON_OWNER} could gather enough timing samples to infer registration
 * status; the happy path also enqueues an invite e-mail, making this an e-mail flood surface too.
 * {@link AuthRateLimitFilter} caps these POSTs at {@code INVITE_CAPACITY=15 / 60 s} per IP via its
 * internally-built {@code inviteBuckets}.
 *
 * <p>This drives the <i>real</i> filter through {@code doFilterInternal} and asserts only
 * observable HTTP behaviour. The invite bucket is built internally (like {@code otpVerifyBuckets}
 * / {@code searchBuckets}), so this test references no new constructor arg; the 16 positional
 * permissive caches mirror the public 16-{@code @Qualifier} constructor.
 */
@DisplayName("AuthRateLimitFilter — POST /auth/invite per-IP throttle (SEC-fix enumeration/timing regression net)")
class InvitePostRateLimitRegressionTest {

    private static final String REMOTE_ADDR = "10.0.0.77";
    private static final String INVITE_PATH = "/api/v1/auth/invite";
    private static final int EXPECTED_CAP = 15;
    // Fired well above the 15/min cap so the bucket is exhausted regardless of the exact capacity.
    private static final int REQUESTS_TO_FIRE = 40;

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
        // 19 permissive caches — one positional arg per @Qualifier bucket on the production
        // constructor (now includes verifyPasswordResetOtpBuckets / changePasswordOtpBuckets,
        // Phase A5). The invite-POST throttle is internal to the filter (built like
        // otpVerifyBuckets), so this test references no new constructor arg beyond the count.
        return new AuthRateLimitFilter(
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive());
    }

    private MockHttpServletRequest postInvite() {
        var req = new MockHttpServletRequest("POST", INVITE_PATH);
        req.setRemoteAddr(REMOTE_ADDR);
        return req;
    }

    @Test
    @DisplayName("should_return429_when_invitePostExceedsPerIpCap")
    void should_return429_when_invitePostExceedsPerIpCap() throws Exception {
        AuthRateLimitFilter filter = realFilter();

        MockHttpServletResponse lastResponse = null;
        MockFilterChain lastChain = null;
        boolean anyThrottled = false;
        int allowedBeforeThrottle = 0;

        for (int i = 0; i < REQUESTS_TO_FIRE; i++) {
            lastResponse = new MockHttpServletResponse();
            lastChain = new MockFilterChain();
            filter.doFilterInternal(postInvite(), lastResponse, lastChain);
            if (lastResponse.getStatus() == 429) {
                anyThrottled = true;
                break;
            }
            allowedBeforeThrottle++;
        }

        assertThat(anyThrottled)
                .as("POST %s must be IP-throttled — %d unthrottled requests from one IP means the "
                        + "invite enumeration/timing flood guard is missing", INVITE_PATH, REQUESTS_TO_FIRE)
                .isTrue();
        assertThat(allowedBeforeThrottle)
                .as("the cap must be the documented %d/min ceiling — request %d is the first throttled one",
                        EXPECTED_CAP, EXPECTED_CAP + 1)
                .isEqualTo(EXPECTED_CAP);
        assertThat(lastResponse.getStatus())
                .as("the throttled invite request must return 429")
                .isEqualTo(429);
        assertThat(lastChain.getRequest())
                .as("the throttled invite request must NOT be forwarded down the filter chain")
                .isNull();
    }

    @Test
    @DisplayName("should_setRetryAfterHeader_when_invitePostThrottled")
    void should_setRetryAfterHeader_when_invitePostThrottled() throws Exception {
        AuthRateLimitFilter filter = realFilter();

        MockHttpServletResponse lastResponse = null;
        for (int i = 0; i < REQUESTS_TO_FIRE; i++) {
            lastResponse = new MockHttpServletResponse();
            filter.doFilterInternal(postInvite(), lastResponse, new MockFilterChain());
            if (lastResponse.getStatus() == 429) {
                break;
            }
        }

        assertThat(lastResponse.getStatus()).isEqualTo(429);
        assertThat(lastResponse.getHeader("Retry-After"))
                .as("a throttled invite must carry a Retry-After header so clients back off")
                .isNotNull();
        assertThat(lastResponse.getContentType())
                .as("the 429 body is the JSON too-many-requests envelope")
                .contains("application/json");
    }
}
