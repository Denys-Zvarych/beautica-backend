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
 * <b>LOW-FIX REGRESSION NET (2026-08-11 — unthrottled public availability read).</b>
 *
 * <p>{@code GET /api/v1/book/{slug}/availability} is {@code permitAll()} in {@code SecurityConfig}
 * and was deliberately EXCLUDED from {@code guestBookingBuckets} ("only the booking POST consumes
 * this bucket"), leaving it with no throttle at all. Each call drives
 * {@code MasterScheduleService#resolveEffectiveDay}, the master's booking-overlap query and the full
 * slot walk for the requested {@code date} — the very oracle the create-path schedule-fit gate now
 * calls — at zero auth cost, so a single source could loop it with a rotating {@code date} and sustain
 * that work unbounded. This class pins the per-IP bucket that closes it.
 *
 * <p><b>Why this is not folded into {@code GuestBookingPostRateLimitRegressionTest}.</b> That class
 * owns the opposite assertion for the same URL space — the booking POST bucket must NOT swallow reads
 * on the shared {@code /api/v1/book/} prefix. Keeping the two separate keeps each one's failure message
 * unambiguous about which bucket regressed.
 *
 * <p>Drives the REAL filter through {@code doFilterInternal} with every injected bucket made permissive,
 * so a 429 here can only come from the internally-built {@code guestAvailabilityBuckets} — no other
 * bucket in the filter can produce it.
 */
@DisplayName("AuthRateLimitFilter — GET /book/{slug}/availability per-IP throttle")
class GuestAvailabilityGetRateLimitTest {

    private static final String REMOTE_ADDR = "10.0.0.11";
    private static final String OTHER_ADDR = "10.0.0.12";
    private static final String AVAILABILITY_PATH = "/api/v1/book/marija-l-cd34/availability";
    private static final String INFO_PATH = "/api/v1/book/marija-l-cd34/info";

    /**
     * Fired well above the 60/60s cap so the bucket is exhausted with margin, and above any plausible
     * retune of it, keeping this test edit-free if the capacity is ever adjusted.
     */
    private static final int REQUESTS_TO_FIRE = 300;

    /**
     * Comfortably inside the cap — the "a real guest browsing dates is never throttled" half of the
     * contract. A human tapping through a whole visible week on the public booking page makes ~7
     * requests; 20 is triple that and must still pass untouched.
     */
    private static final int LEGITIMATE_BROWSING_REQUESTS = 20;

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

    /**
     * 19 permissive caches — one positional arg per {@code @Qualifier} bucket on the production
     * constructor. The availability throttle is built INTERNALLY (like {@code guestBookingBuckets} /
     * {@code cancelPostBuckets}), so it is deliberately not reachable from here: that is what makes a
     * 429 below attributable to it alone.
     */
    private AuthRateLimitFilter realFilter() {
        return new AuthRateLimitFilter(
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive(), permissive(),
                permissive(), permissive(), permissive());
    }

    private static MockHttpServletRequest get(String path, String remoteAddr) {
        var req = new MockHttpServletRequest("GET", path);
        req.setRemoteAddr(remoteAddr);
        return req;
    }

    @Test
    @DisplayName("should_return429_when_guestAvailabilityFloodedFromOneIp")
    void should_return429_when_guestAvailabilityFloodedFromOneIp() throws Exception {
        AuthRateLimitFilter filter = realFilter();

        MockHttpServletResponse lastResponse = null;
        MockFilterChain lastChain = null;
        boolean anyThrottled = false;

        for (int i = 0; i < REQUESTS_TO_FIRE && !anyThrottled; i++) {
            lastResponse = new MockHttpServletResponse();
            lastChain = new MockFilterChain();
            filter.doFilterInternal(get(AVAILABILITY_PATH, REMOTE_ADDR), lastResponse, lastChain);
            anyThrottled = lastResponse.getStatus() == 429;
        }

        assertThat(anyThrottled)
                .as("GET /book/{slug}/availability must be IP-throttled — %d unthrottled requests from "
                        + "one IP means the public availability flood guard is missing", REQUESTS_TO_FIRE)
                .isTrue();
        assertThat(lastResponse.getStatus())
                .as("the throttled availability request must return 429")
                .isEqualTo(429);
        assertThat(lastResponse.getHeader("Retry-After"))
                .as("a throttled caller must be told when to come back, so a client honouring "
                        + "Retry-After does not spin")
                .isNotNull();
        assertThat(lastChain.getRequest())
                .as("the throttled availability request must NOT be forwarded down the filter chain — "
                        + "the whole point is that the slot walk never runs")
                .isNull();
    }

    @Test
    @DisplayName("should_notThrottle_when_aGuestBrowsesDatesAtHumanRate")
    void should_notThrottle_when_aGuestBrowsesDatesAtHumanRate() throws Exception {
        // Non-vacuity for the flood test above: the cap must reject a flood WITHOUT rejecting the
        // legitimate traffic it exists to protect. A budget that also 429s a guest tapping through a
        // week of dates would be an availability regression, not a control.
        AuthRateLimitFilter filter = realFilter();

        for (int i = 0; i < LEGITIMATE_BROWSING_REQUESTS; i++) {
            var response = new MockHttpServletResponse();
            var chain = new MockFilterChain();

            filter.doFilterInternal(get(AVAILABILITY_PATH, REMOTE_ADDR), response, chain);

            assertThat(response.getStatus())
                    .as("request %d of a normal date-browsing session must pass", i + 1)
                    .isNotEqualTo(429);
            assertThat(chain.getRequest())
                    .as("request %d of a normal date-browsing session must be forwarded", i + 1)
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("should_throttlePerIp_when_oneSourceIsAlreadyExhausted")
    void should_throttlePerIp_when_oneSourceIsAlreadyExhausted() throws Exception {
        // The bucket is keyed per source IP, not global: one abusive source must not lock every other
        // guest out of the public booking page.
        AuthRateLimitFilter filter = realFilter();

        for (int i = 0; i < REQUESTS_TO_FIRE; i++) {
            filter.doFilterInternal(get(AVAILABILITY_PATH, REMOTE_ADDR),
                    new MockHttpServletResponse(), new MockFilterChain());
        }

        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        filter.doFilterInternal(get(AVAILABILITY_PATH, OTHER_ADDR), response, chain);

        assertThat(response.getStatus())
                .as("a second IP must keep its own budget after the first exhausted theirs")
                .isNotEqualTo(429);
        assertThat(chain.getRequest())
                .as("the second IP's request must be forwarded")
                .isNotNull();
    }

    @Test
    @DisplayName("should_notConsumeTheAvailabilityBucket_when_readingTheSiblingInfoEndpoint")
    void should_notConsumeTheAvailabilityBucket_when_readingTheSiblingInfoEndpoint() throws Exception {
        // Matcher-width guard: the rule is prefix /api/v1/book/ PLUS suffix /availability. GET
        // /book/{slug}/info shares the prefix and must stay outside this bucket — otherwise the fix
        // silently throttled every public read under /book, including the page load itself.
        AuthRateLimitFilter filter = realFilter();

        for (int i = 0; i < REQUESTS_TO_FIRE; i++) {
            filter.doFilterInternal(get(AVAILABILITY_PATH, REMOTE_ADDR),
                    new MockHttpServletResponse(), new MockFilterChain());
        }

        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        filter.doFilterInternal(get(INFO_PATH, REMOTE_ADDR), response, chain);

        assertThat(response.getStatus())
                .as("GET /book/{slug}/info must not be throttled by the availability bucket")
                .isNotEqualTo(429);
        assertThat(chain.getRequest())
                .as("GET /book/{slug}/info must be forwarded")
                .isNotNull();
    }
}
