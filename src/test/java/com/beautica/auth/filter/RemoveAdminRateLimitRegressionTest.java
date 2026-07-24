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
 * <b>SEC-FIX REGRESSION NET — per-IP throttle on DELETE /api/v1/salons/{salonId}/admins/{userId}
 * (Phase 21.2 remove-admin flood/probe guard).</b>
 *
 * <p>{@code DELETE /api/v1/salons/{salonId}/admins/{userId}} (SalonController#removeAdmin) is the
 * actual HTTP path through which SALON_OWNER and SALON_ADMIN callers reach
 * {@code SalonService.removeAdmin}. The {@code canManageSalon} + {@code adminBelongsToSalon}
 * composition in its {@code @PreAuthorize} SpEL is correct and out of scope here — this class only
 * proves the compensating IP-layer control for the surface that was previously completely
 * unthrottled: before this fix the path fell through to the unmatched-DELETE branch of
 * {@link AuthRateLimitFilter#doFilterInternal}, letting an authorized actor script rapid-fire
 * admin removals or probe many {@code userId} values within their own salon with no per-IP cap.
 * This mirrors the {@code salonInviteBuckets} fix from Phase 21.1
 * ({@code SalonInvitePostRateLimitRegressionTest}). This filter now caps these DELETEs at
 * {@code REMOVE_ADMIN_CAPACITY=10 / 60 s} per IP via its internally-built {@code removeAdminBuckets}.
 *
 * <p>This drives the <i>real</i> filter through {@code doFilterInternal} and asserts only
 * observable HTTP behaviour. The remove-admin bucket is built internally (like
 * {@code salonInviteBuckets} / {@code logoutBuckets}), so this test references no new constructor
 * arg; the 18 positional permissive caches mirror the public 18-{@code @Qualifier} constructor
 * (see {@code SalonInvitePostRateLimitRegressionTest}).
 */
@DisplayName("AuthRateLimitFilter — DELETE /salons/{salonId}/admins/{userId} per-IP throttle (Phase 21.2 SEC-fix regression net)")
class RemoveAdminRateLimitRegressionTest {

    private static final String REMOTE_ADDR = "10.0.0.99";
    private static final int EXPECTED_CAP = 10;
    // Fired well above the 10/min cap so the bucket is exhausted regardless of the exact capacity.
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
        // 18 permissive caches — one positional arg per @Qualifier bucket on the production
        // constructor. The remove-admin-DELETE throttle is internal to the filter (built like
        // salonInviteBuckets), so this test references no new constructor arg beyond the count.
        return new AuthRateLimitFilter(
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive());
    }

    private MockHttpServletRequest deleteAdmin(UUID salonId, UUID userId) {
        var req = new MockHttpServletRequest("DELETE",
                "/api/v1/salons/" + salonId + "/admins/" + userId);
        req.setRemoteAddr(REMOTE_ADDR);
        return req;
    }

    @Test
    @DisplayName("should_return429_when_removeAdminDeleteExceedsPerIpCap")
    void should_return429_when_removeAdminDeleteExceedsPerIpCap() throws Exception {
        AuthRateLimitFilter filter = realFilter();

        MockHttpServletResponse lastResponse = null;
        MockFilterChain lastChain = null;
        boolean anyThrottled = false;
        int allowedBeforeThrottle = 0;

        for (int i = 0; i < REQUESTS_TO_FIRE; i++) {
            lastResponse = new MockHttpServletResponse();
            lastChain = new MockFilterChain();
            // A different {salonId}/{userId} pair on every call — the bucket is IP-keyed, not
            // path-keyed, so varying both path segments proves the throttle is not accidentally
            // scoped per-salon or per-target-user (i.e. it also stops userId-probing).
            filter.doFilterInternal(deleteAdmin(UUID.randomUUID(), UUID.randomUUID()), lastResponse, lastChain);
            if (lastResponse.getStatus() == 429) {
                anyThrottled = true;
                break;
            }
            allowedBeforeThrottle++;
        }

        assertThat(anyThrottled)
                .as("DELETE /api/v1/salons/{salonId}/admins/{userId} must be IP-throttled — %d unthrottled "
                        + "requests from one IP means the remove-admin flood/probe guard is missing",
                        REQUESTS_TO_FIRE)
                .isTrue();
        assertThat(allowedBeforeThrottle)
                .as("the cap must be the documented %d/min ceiling — request %d is the first throttled one",
                        EXPECTED_CAP, EXPECTED_CAP + 1)
                .isEqualTo(EXPECTED_CAP);
        assertThat(lastResponse.getStatus())
                .as("the throttled remove-admin request must return 429")
                .isEqualTo(429);
        assertThat(lastChain.getRequest())
                .as("the throttled remove-admin request must NOT be forwarded down the filter chain")
                .isNull();
    }

    @Test
    @DisplayName("should_setRetryAfterHeader_when_removeAdminDeleteThrottled")
    void should_setRetryAfterHeader_when_removeAdminDeleteThrottled() throws Exception {
        AuthRateLimitFilter filter = realFilter();
        UUID salonId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        MockHttpServletResponse lastResponse = null;
        for (int i = 0; i < REQUESTS_TO_FIRE; i++) {
            lastResponse = new MockHttpServletResponse();
            filter.doFilterInternal(deleteAdmin(salonId, userId), lastResponse, new MockFilterChain());
            if (lastResponse.getStatus() == 429) {
                break;
            }
        }

        assertThat(lastResponse.getStatus()).isEqualTo(429);
        assertThat(lastResponse.getHeader("Retry-After"))
                .as("a throttled remove-admin request must carry a Retry-After header so clients back off")
                .isNotNull();
        assertThat(lastResponse.getContentType())
                .as("the 429 body is the JSON too-many-requests envelope")
                .contains("application/json");
    }

    @Test
    @DisplayName("should_notThrottleUnrelatedSalonPath_when_pathIsNotAdminsSegment")
    void should_notThrottleUnrelatedSalonPath_when_pathIsNotAdminsSegment() throws Exception {
        // Negative control for the prefix+contains matching itself: the sibling salon DELETE
        // route (DELETE /api/v1/salons/{salonId} — SalonController#deactivateSalon) shares the
        // same prefix and HTTP method but has no "/admins/" segment, so it must not be captured
        // by the new removeAdminBuckets branch nor starved by it.
        AuthRateLimitFilter filter = realFilter();
        UUID salonId = UUID.randomUUID();
        var req = new MockHttpServletRequest("DELETE", "/api/v1/salons/" + salonId);
        req.setRemoteAddr(REMOTE_ADDR);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilterInternal(req, response, chain);

        assertThat(response.getStatus())
                .as("the sibling deactivate-salon DELETE path must not be rejected by the remove-admin bucket")
                .isNotEqualTo(429);
        assertThat(chain.getRequest())
                .as("the sibling deactivate-salon DELETE path must still be forwarded down the filter chain")
                .isNotNull();
    }
}
