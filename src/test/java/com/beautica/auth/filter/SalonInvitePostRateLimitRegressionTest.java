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
 * <b>SEC-FIX REGRESSION NET — per-IP throttle on POST /api/v1/salons/{salonId}/invite (timing/
 * enumeration + invite-email flood guard).</b>
 *
 * <p>{@code POST /api/v1/salons/{salonId}/invite} (SalonController#inviteMaster) is the actual
 * HTTP path through which SALON_OWNER — and, since the Phase 21.1 multi-admin relaxation,
 * SALON_ADMIN — callers reach {@code InviteService.sendInvite}. Before this fix the path fell
 * through to the unmatched {@code else} branch of {@link AuthRateLimitFilter#doFilterInternal}
 * with no throttle at all, even though it shares the exact same residual timing/enumeration
 * side-channel as {@code POST /api/v1/auth/invite} (see {@code InvitePostRateLimitRegressionTest}):
 * the already-registered and active-invite branches of {@code sendInvite} do measurably less work
 * than the brand-new branch, and Phase 21.1 widened the population that can probe it. This filter
 * now caps these POSTs at {@code SALON_INVITE_CAPACITY=15 / 60 s} per IP via its internally-built
 * {@code salonInviteBuckets}.
 *
 * <p>This drives the <i>real</i> filter through {@code doFilterInternal} and asserts only
 * observable HTTP behaviour. The salon-invite bucket is built internally (like
 * {@code inviteBuckets} / {@code otpVerifyBuckets} / {@code searchBuckets}), so this test
 * references no new constructor arg; the 19 positional permissive caches mirror the public
 * 19-{@code @Qualifier} constructor.
 */
@DisplayName("AuthRateLimitFilter — POST /salons/{salonId}/invite per-IP throttle (SEC-fix enumeration/timing regression net)")
class SalonInvitePostRateLimitRegressionTest {

    private static final String REMOTE_ADDR = "10.0.0.88";
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
        // constructor (includes verifyPasswordResetOtpBuckets / changePasswordOtpBuckets, Phase
        // A5). The salon-invite-POST throttle is internal to the filter (built like
        // inviteBuckets), so this test references no new constructor arg beyond the count.
        return new AuthRateLimitFilter(
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive());
    }

    private MockHttpServletRequest postSalonInvite(UUID salonId) {
        var req = new MockHttpServletRequest("POST", "/api/v1/salons/" + salonId + "/invite");
        req.setRemoteAddr(REMOTE_ADDR);
        return req;
    }

    @Test
    @DisplayName("should_return429_when_salonInvitePostExceedsPerIpCap")
    void should_return429_when_salonInvitePostExceedsPerIpCap() throws Exception {
        AuthRateLimitFilter filter = realFilter();

        MockHttpServletResponse lastResponse = null;
        MockFilterChain lastChain = null;
        boolean anyThrottled = false;
        int allowedBeforeThrottle = 0;

        for (int i = 0; i < REQUESTS_TO_FIRE; i++) {
            lastResponse = new MockHttpServletResponse();
            lastChain = new MockFilterChain();
            // A different {salonId} on every call — the bucket is IP-keyed, not path-keyed, so
            // varying the path segment proves the throttle is not accidentally scoped per-salon.
            filter.doFilterInternal(postSalonInvite(UUID.randomUUID()), lastResponse, lastChain);
            if (lastResponse.getStatus() == 429) {
                anyThrottled = true;
                break;
            }
            allowedBeforeThrottle++;
        }

        assertThat(anyThrottled)
                .as("POST /api/v1/salons/{salonId}/invite must be IP-throttled — %d unthrottled "
                        + "requests from one IP means the invite enumeration/timing flood guard is missing",
                        REQUESTS_TO_FIRE)
                .isTrue();
        assertThat(allowedBeforeThrottle)
                .as("the cap must be the documented %d/min ceiling — request %d is the first throttled one",
                        EXPECTED_CAP, EXPECTED_CAP + 1)
                .isEqualTo(EXPECTED_CAP);
        assertThat(lastResponse.getStatus())
                .as("the throttled salon-invite request must return 429")
                .isEqualTo(429);
        assertThat(lastChain.getRequest())
                .as("the throttled salon-invite request must NOT be forwarded down the filter chain")
                .isNull();
    }

    @Test
    @DisplayName("should_setRetryAfterHeader_when_salonInvitePostThrottled")
    void should_setRetryAfterHeader_when_salonInvitePostThrottled() throws Exception {
        AuthRateLimitFilter filter = realFilter();
        UUID salonId = UUID.randomUUID();

        MockHttpServletResponse lastResponse = null;
        for (int i = 0; i < REQUESTS_TO_FIRE; i++) {
            lastResponse = new MockHttpServletResponse();
            filter.doFilterInternal(postSalonInvite(salonId), lastResponse, new MockFilterChain());
            if (lastResponse.getStatus() == 429) {
                break;
            }
        }

        assertThat(lastResponse.getStatus()).isEqualTo(429);
        assertThat(lastResponse.getHeader("Retry-After"))
                .as("a throttled salon-invite must carry a Retry-After header so clients back off")
                .isNotNull();
        assertThat(lastResponse.getContentType())
                .as("the 429 body is the JSON too-many-requests envelope")
                .contains("application/json");
    }

    @Test
    @DisplayName("should_notThrottleUnrelatedSalonPath_when_pathIsNotInviteSuffix")
    void should_notThrottleUnrelatedSalonPath_when_pathIsNotInviteSuffix() throws Exception {
        // Regression net for the prefix+suffix matching itself: a sibling salon POST path that
        // does NOT end in /invite (e.g. the bulk-service-setup route) must not be captured by
        // the new salonInviteBuckets branch and must not be starved by it either.
        AuthRateLimitFilter filter = realFilter();
        UUID salonId = UUID.randomUUID();
        var req = new MockHttpServletRequest("POST", "/api/v1/salons/" + salonId + "/services/bulk");
        req.setRemoteAddr(REMOTE_ADDR);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilterInternal(req, response, chain);

        assertThat(response.getStatus())
                .as("a non-invite salon POST path must not be rejected by the salon-invite bucket")
                .isNotEqualTo(429);
    }
}
