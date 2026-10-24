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
 * <b>HIGH-FIX REGRESSION NET (Phase 13.3 — uncapped guest-booking POST, IP layer).</b>
 *
 * <p>{@code POST /api/v1/book/{slug}/booking} is {@code permitAll()} in {@code SecurityConfig}
 * (the guest JWT is validated inside {@link com.beautica.booking.service.GuestBookingService},
 * not the Spring filter chain). With no IP-layer throttle a single holder of one valid guest
 * token — or an attacker replaying a captured token — can pour booking attempts at a master,
 * each one running the full slug+service resolution and advisory-lock path. This guard caps
 * the booking POST per source IP, mirroring the {@code otpSendBuckets} / {@code otpVerifyBuckets}
 * defences already in {@link AuthRateLimitFilter}.
 *
 * <p><b>EXPECTED-RED until backend-dev adds the {@code POST /api/v1/book/{slug}/booking} branch +
 * per-IP bucket to {@link AuthRateLimitFilter}.</b> It drives the <i>real</i> filter through
 * {@code doFilterInternal} and asserts only observable HTTP behaviour (a throttled request
 * returns 429 and is not forwarded), so it needs no edit once the bucket is wired internally —
 * the assertion flips from RED to GREEN. Against current code the booking POST falls through the
 * {@code else} branch (forwarded, never throttled), so {@code anyThrottled} stays false (RED now).
 *
 * <p>The path uses a literal slug segment matching the production matcher
 * {@code /api/v1/book/{slug}/booking}; the security matcher wildcard matches exactly one path
 * segment, so a concrete slug is required here.
 */
@DisplayName("AuthRateLimitFilter — POST /book/{slug}/booking per-IP throttle (HIGH-fix regression, expected-red until fix lands)")
class GuestBookingPostRateLimitRegressionTest {

    private static final String REMOTE_ADDR = "10.0.0.7";
    private static final String BOOKING_PATH = "/api/v1/book/marija-l-cd34/booking";

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
        // 19 permissive caches — one positional arg per @Qualifier bucket on the production
        // constructor (now includes verifyPasswordResetOtpBuckets / changePasswordOtpBuckets,
        // Phase A5). The booking-POST throttle the fix adds is internal to the filter (built
        // like otpVerifyBuckets), so this test does not reference any new constructor arg beyond
        // the count: it asserts only on observable HTTP behaviour and stays compatible once the
        // bucket is wired.
        return new AuthRateLimitFilter(
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(), permissive(), permissive(), permissive(), permissive(), permissive());
    }

    private MockHttpServletRequest postBooking() {
        var req = new MockHttpServletRequest("POST", BOOKING_PATH);
        req.setRemoteAddr(REMOTE_ADDR);
        return req;
    }

    @Test
    @DisplayName("should_return429_when_guestBookingPostExceedsPerIpCap")
    void should_return429_when_guestBookingPostExceedsPerIpCap() throws Exception {
        AuthRateLimitFilter filter = realFilter();

        MockHttpServletResponse lastResponse = null;
        MockFilterChain lastChain = null;
        boolean anyThrottled = false;

        for (int i = 0; i < REQUESTS_TO_FIRE; i++) {
            lastResponse = new MockHttpServletResponse();
            lastChain = new MockFilterChain();
            filter.doFilterInternal(postBooking(), lastResponse, lastChain);
            if (lastResponse.getStatus() == 429) {
                anyThrottled = true;
                break;
            }
        }

        assertThat(anyThrottled)
                .as("POST /book/{slug}/booking must be IP-throttled — %d unthrottled requests from "
                        + "one IP means the guest-booking flood guard is missing", REQUESTS_TO_FIRE)
                .isTrue();
        assertThat(lastResponse.getStatus())
                .as("the throttled booking request must return 429")
                .isEqualTo(429);
        assertThat(lastChain.getRequest())
                .as("the throttled booking request must NOT be forwarded down the filter chain")
                .isNull();
    }

    @Test
    @DisplayName("should_passThrough_when_getAvailability (unauthenticated reads are not booking-throttled)")
    void should_passThrough_when_getAvailability() throws Exception {
        AuthRateLimitFilter filter = realFilter();

        // A read on the same /book/** prefix must NOT consume the booking bucket — guards
        // against the fix over-broadening its matcher to all /book/** traffic.
        //
        // Since 2026-08-11 this GET has a bucket of its OWN (guestAvailabilityBuckets, 60/60s — see
        // GuestAvailabilityGetRateLimitTest); this assertion is unchanged and still means what it always
        // did: ONE read must not be charged to the 10/15min BOOKING budget. One request is two orders of
        // magnitude inside the availability cap, so it passes on that bucket too.
        var get = new MockHttpServletRequest("GET", "/api/v1/book/marija-l-cd34/availability");
        get.setRemoteAddr(REMOTE_ADDR);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilterInternal(get, response, chain);

        assertThat(response.getStatus())
                .as("GET availability must pass through (not 429)")
                .isNotEqualTo(429);
        assertThat(chain.getRequest())
                .as("GET availability must be forwarded down the filter chain")
                .isNotNull();
    }

    // ── phases 392/393 — salon-scoped twins share the master routes' buckets ────────────────────

    private static final String SALON_BOOKING_PATH =
            "/api/v1/book/s/studiia-krasa-ab12/masters/2f7a1c3e-1b2d-4c5e-8f90-123456789abc/booking";

    @Test
    @DisplayName("phase 393: salon POST /book/s/{slug}/masters/{id}/booking draws the SAME 10/15min guest-booking "
            + "bucket — 10 master-route POSTs leave the salon POST 429 (no second budget for a split visit)")
    void should_shareGuestBookingBucket_when_salonBookingPostAfterMasterPosts() throws Exception {
        AuthRateLimitFilter filter = realFilter();
        int allowed = 0;
        for (int i = 0; i < REQUESTS_TO_FIRE; i++) {
            var response = new MockHttpServletResponse();
            filter.doFilterInternal(postBooking(), response, new MockFilterChain());
            if (response.getStatus() == 429) {
                break;
            }
            allowed++;
        }
        var salonPost = new MockHttpServletRequest("POST", SALON_BOOKING_PATH);
        salonPost.setRemoteAddr(REMOTE_ADDR);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilterInternal(salonPost, response, chain);

        assertThat(allowed).as("non-vacuous: the master route is capped at 10 (phase 393 audit)").isEqualTo(10);
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("900");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    @DisplayName("phase 393 audit: salon booking POST alone is capped at 10 per IP per 15 min — one max-size visit "
            + "(10 masters) fits, the 11th is 429")
    void should_capSalonBookingPost_when_floodedFromOneIp() throws Exception {
        AuthRateLimitFilter filter = realFilter();
        int allowed = 0;

        for (int i = 0; i < REQUESTS_TO_FIRE; i++) {
            var req = new MockHttpServletRequest("POST", SALON_BOOKING_PATH);
            req.setRemoteAddr(REMOTE_ADDR);
            var response = new MockHttpServletResponse();
            filter.doFilterInternal(req, response, new MockFilterChain());
            if (response.getStatus() == 429) {
                break;
            }
            allowed++;
        }

        assertThat(allowed).isEqualTo(10);
    }
}
