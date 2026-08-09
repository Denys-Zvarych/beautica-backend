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
 * <b>SEC-FIX REGRESSION NET — per-IP throttle shared by the two Phase 21.3 staff-rotation PATCH
 * endpoints:</b>
 * <ul>
 *   <li>{@code PATCH /api/v1/salons/{salonId}/admins/{userId}/salon} (SalonController#rotateAdmin)</li>
 *   <li>{@code PATCH /api/v1/masters/{masterId}/salon} (MasterController#rotateMasterSalon)</li>
 * </ul>
 *
 * <p>Both routes' authorization logic ({@code canManageSalon}/{@code adminBelongsToSalon} and
 * {@code canManageMaster}, composed with the service-layer {@code salonsShareOwner} same-owner
 * guard) is correct and out of scope here — this class only proves the compensating IP-layer
 * control for the surface that was previously completely unthrottled: before this fix both paths
 * fell through to the unmatched-PATCH branch of {@link AuthRateLimitFilter#doFilterInternal},
 * letting an authorized actor script rapid-fire staff rotations or probe many destination-salon
 * ids within their own portfolio with no per-IP cap. This mirrors the {@code removeAdminBuckets}
 * fix from Phase 21.2 ({@code RemoveAdminRateLimitRegressionTest}), except the two endpoints
 * deliberately share ONE bucket ({@code rotateStaffBuckets}) rather than two, since they are the
 * same class of low-frequency admin action.
 *
 * <p>This drives the <i>real</i> filter through {@code doFilterInternal} and asserts only
 * observable HTTP behaviour. The rotate-staff bucket is built internally (like
 * {@code removeAdminBuckets} / {@code salonInviteBuckets}), so this test references no new
 * constructor arg; the 19 positional permissive caches mirror the public 19-{@code @Qualifier}
 * constructor (see {@code SalonInvitePostRateLimitRegressionTest}).
 */
@DisplayName("AuthRateLimitFilter — PATCH staff-rotation endpoints shared per-IP throttle (Phase 21.3 SEC-fix regression net)")
class RotateStaffRateLimitRegressionTest {

    private static final String REMOTE_ADDR = "10.0.0.77";
    // Raised from 10 to 30 (see ROTATE_STAFF_CAPACITY on AuthRateLimitFilter): 10/min silently
    // 429'd realistic bulk staff-reshuffle usage and caused shared-IP CI flakiness across
    // SalonAdminRotationIntegrationTest + MasterRotationIntegrationTest run together.
    private static final int EXPECTED_CAP = 30;
    // Fired well above the 30/min cap so the bucket is exhausted regardless of the exact capacity.
    private static final int REQUESTS_TO_FIRE = 100;

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
        // constructor. The rotate-staff-PATCH throttle is internal to the filter (built like
        // removeAdminBuckets), so this test references no new constructor arg beyond the count.
        return new AuthRateLimitFilter(
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive());
    }

    private MockHttpServletRequest rotateAdmin(UUID salonId, UUID userId) {
        var req = new MockHttpServletRequest("PATCH",
                "/api/v1/salons/" + salonId + "/admins/" + userId + "/salon");
        req.setRemoteAddr(REMOTE_ADDR);
        return req;
    }

    private MockHttpServletRequest rotateMaster(UUID masterId) {
        var req = new MockHttpServletRequest("PATCH", "/api/v1/masters/" + masterId + "/salon");
        req.setRemoteAddr(REMOTE_ADDR);
        return req;
    }

    @Test
    @DisplayName("should_return429_when_rotateAdminPatchExceedsPerIpCap")
    void should_return429_when_rotateAdminPatchExceedsPerIpCap() throws Exception {
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
            // scoped per-salon or per-target-user (i.e. it also stops destination-id probing).
            filter.doFilterInternal(rotateAdmin(UUID.randomUUID(), UUID.randomUUID()), lastResponse, lastChain);
            if (lastResponse.getStatus() == 429) {
                anyThrottled = true;
                break;
            }
            allowedBeforeThrottle++;
        }

        assertThat(anyThrottled)
                .as("PATCH .../admins/{userId}/salon must be IP-throttled — %d unthrottled requests "
                        + "from one IP means the staff-rotation flood/probe guard is missing",
                        REQUESTS_TO_FIRE)
                .isTrue();
        assertThat(allowedBeforeThrottle)
                .as("the cap must be the documented %d/min ceiling — request %d is the first throttled one",
                        EXPECTED_CAP, EXPECTED_CAP + 1)
                .isEqualTo(EXPECTED_CAP);
        assertThat(lastResponse.getStatus())
                .as("the throttled rotate-admin request must return 429")
                .isEqualTo(429);
        assertThat(lastChain.getRequest())
                .as("the throttled rotate-admin request must NOT be forwarded down the filter chain")
                .isNull();
    }

    @Test
    @DisplayName("should_return429_when_rotateMasterPatchExceedsPerIpCap")
    void should_return429_when_rotateMasterPatchExceedsPerIpCap() throws Exception {
        AuthRateLimitFilter filter = realFilter();

        MockHttpServletResponse lastResponse = null;
        boolean anyThrottled = false;
        int allowedBeforeThrottle = 0;

        for (int i = 0; i < REQUESTS_TO_FIRE; i++) {
            lastResponse = new MockHttpServletResponse();
            filter.doFilterInternal(rotateMaster(UUID.randomUUID()), lastResponse, new MockFilterChain());
            if (lastResponse.getStatus() == 429) {
                anyThrottled = true;
                break;
            }
            allowedBeforeThrottle++;
        }

        assertThat(anyThrottled)
                .as("PATCH /api/v1/masters/{masterId}/salon must be IP-throttled")
                .isTrue();
        assertThat(allowedBeforeThrottle)
                .as("the cap must be the documented %d/min ceiling", EXPECTED_CAP)
                .isEqualTo(EXPECTED_CAP);
    }

    @Test
    @DisplayName("should_shareOneBucket_when_bothRotationEndpointsAreCalledFromTheSameIp")
    void should_shareOneBucket_when_bothRotationEndpointsAreCalledFromTheSameIp() throws Exception {
        // Proves the two endpoints deliberately share ONE bucket rather than two: alternating
        // between them from the same IP must exhaust the shared cap at exactly EXPECTED_CAP total
        // requests, not EXPECTED_CAP per path.
        AuthRateLimitFilter filter = realFilter();

        MockHttpServletResponse lastResponse = null;
        boolean anyThrottled = false;
        int allowedBeforeThrottle = 0;

        for (int i = 0; i < REQUESTS_TO_FIRE; i++) {
            lastResponse = new MockHttpServletResponse();
            MockHttpServletRequest req = (i % 2 == 0)
                    ? rotateAdmin(UUID.randomUUID(), UUID.randomUUID())
                    : rotateMaster(UUID.randomUUID());
            filter.doFilterInternal(req, lastResponse, new MockFilterChain());
            if (lastResponse.getStatus() == 429) {
                anyThrottled = true;
                break;
            }
            allowedBeforeThrottle++;
        }

        assertThat(anyThrottled).isTrue();
        assertThat(allowedBeforeThrottle)
                .as("alternating between both rotation endpoints must exhaust the SAME shared "
                        + "bucket at the documented %d/min ceiling — a distinct-buckets bug would "
                        + "double this to %d", EXPECTED_CAP, EXPECTED_CAP * 2)
                .isEqualTo(EXPECTED_CAP);
    }

    @Test
    @DisplayName("should_setRetryAfterHeader_when_rotateAdminThrottled")
    void should_setRetryAfterHeader_when_rotateAdminThrottled() throws Exception {
        AuthRateLimitFilter filter = realFilter();
        UUID salonId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        MockHttpServletResponse lastResponse = null;
        for (int i = 0; i < REQUESTS_TO_FIRE; i++) {
            lastResponse = new MockHttpServletResponse();
            filter.doFilterInternal(rotateAdmin(salonId, userId), lastResponse, new MockFilterChain());
            if (lastResponse.getStatus() == 429) {
                break;
            }
        }

        assertThat(lastResponse.getStatus()).isEqualTo(429);
        assertThat(lastResponse.getHeader("Retry-After"))
                .as("a throttled rotate-admin request must carry a Retry-After header so clients back off")
                .isNotNull();
        assertThat(lastResponse.getContentType())
                .as("the 429 body is the JSON too-many-requests envelope")
                .contains("application/json");
    }

    @Test
    @DisplayName("should_notThrottleUnrelatedSalonPath_when_pathIsRemoveAdminNotRotateAdmin")
    void should_notThrottleUnrelatedSalonPath_when_pathIsRemoveAdminNotRotateAdmin() throws Exception {
        // Negative control for the prefix+segment+suffix matching itself: the sibling DELETE
        // /api/v1/salons/{salonId}/admins/{userId} route (SalonController#removeAdmin) shares the
        // same prefix and "/admins/" segment but has NO "/salon" suffix and is a different HTTP
        // method — it must not be captured by the new rotateStaffBuckets branch.
        AuthRateLimitFilter filter = realFilter();
        UUID salonId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        var req = new MockHttpServletRequest("DELETE",
                "/api/v1/salons/" + salonId + "/admins/" + userId);
        req.setRemoteAddr(REMOTE_ADDR);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilterInternal(req, response, chain);

        assertThat(response.getStatus())
                .as("the sibling remove-admin DELETE path must not be rejected by the rotate-staff bucket")
                .isNotEqualTo(429);
        assertThat(chain.getRequest())
                .as("the sibling remove-admin DELETE path must still be forwarded down the filter chain")
                .isNotNull();
    }

    @Test
    @DisplayName("should_notThrottleUnrelatedSalonPath_when_pathIsUpdateSalonNotRotateAdmin")
    void should_notThrottleUnrelatedSalonPath_when_pathIsUpdateSalonNotRotateAdmin() throws Exception {
        // Negative control: the sibling PATCH /api/v1/salons/{salonId} route (updateSalon) shares
        // the same prefix and HTTP method but has no "/admins/" segment nor "/salon" suffix — it
        // must not be captured by the rotate-staff bucket.
        AuthRateLimitFilter filter = realFilter();
        UUID salonId = UUID.randomUUID();
        var req = new MockHttpServletRequest("PATCH", "/api/v1/salons/" + salonId);
        req.setRemoteAddr(REMOTE_ADDR);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilterInternal(req, response, chain);

        assertThat(response.getStatus())
                .as("the sibling update-salon PATCH path must not be rejected by the rotate-staff bucket")
                .isNotEqualTo(429);
        assertThat(chain.getRequest())
                .as("the sibling update-salon PATCH path must still be forwarded down the filter chain")
                .isNotNull();
    }
}
