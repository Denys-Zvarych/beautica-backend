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
        // 16 permissive caches — one positional arg per @Qualifier bucket on the production
        // constructor. The booking-POST throttle the fix adds is internal to the filter (built
        // like otpVerifyBuckets), so this test does not reference any new constructor arg: it
        // asserts only on observable HTTP behaviour and stays compatible once the bucket is wired.
        return new AuthRateLimitFilter(
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive());
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
}
