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
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;

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
    private static final String BOOKABLE_DAYS_PATH = "/api/v1/book/marija-l-cd34/bookable-days";

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
                permissive(), permissive(), permissive(), permissive(), permissive(), permissive(), permissive(), permissive(), permissive());
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

    @Test
    @DisplayName("should_return429_when_guestAvailabilityHeadFloodedFromOneIp")
    void should_return429_when_guestAvailabilityHeadFloodedFromOneIp() throws Exception {
        AuthRateLimitFilter filter = realFilter();
        var head = new MockHttpServletRequest("HEAD", AVAILABILITY_PATH);
        head.setRemoteAddr(REMOTE_ADDR);

        MockHttpServletResponse last = null;
        MockFilterChain lastChain = null;
        boolean throttled = false;
        for (int i = 0; i < REQUESTS_TO_FIRE && !throttled; i++) {
            last = new MockHttpServletResponse();
            lastChain = new MockFilterChain();
            filter.doFilterInternal(head, last, lastChain);
            throttled = last.getStatus() == 429;
        }

        assertThat(throttled)
                .as("HEAD is routed by Spring to the @GetMapping handler, so it must be throttled like GET")
                .isTrue();
        assertThat(lastChain.getRequest()).as("throttled HEAD must not reach the chain").isNull();
    }

    @Test
    @DisplayName("should_return429_when_guestBookableDaysFloodedFromOneIp (phase 387)")
    void should_return429_when_guestBookableDaysFloodedFromOneIp() throws Exception {
        AuthRateLimitFilter filter = realFilter();

        MockHttpServletResponse lastResponse = null;
        MockFilterChain lastChain = null;
        for (int i = 0; i < REQUESTS_TO_FIRE && (lastResponse == null || lastResponse.getStatus() != 429); i++) {
            lastResponse = new MockHttpServletResponse();
            lastChain = new MockFilterChain();
            filter.doFilterInternal(get(BOOKABLE_DAYS_PATH, REMOTE_ADDR), lastResponse, lastChain);
        }

        assertThat(lastResponse.getStatus())
                .as("GET /book/{slug}/bookable-days drives the same slot oracle over a whole window — it must be "
                        + "IP-throttled, not fall through to the unthrottled GET path")
                .isEqualTo(429);
        assertThat(lastChain.getRequest()).as("the throttled request must not be forwarded").isNull();
    }

    @Test
    @DisplayName("should_shareOneBudget_when_availabilityExhaustedThenBookableDaysRead (phase 387)")
    void should_shareOneBudget_when_availabilityExhaustedThenBookableDaysRead() throws Exception {
        // One budget per guest page, mirroring /masters/{id}/slots + /working-days sharing slotsBuckets: a
        // caller cannot double its oracle budget by alternating the two routes.
        AuthRateLimitFilter filter = realFilter();
        for (int i = 0; i < REQUESTS_TO_FIRE; i++) {
            filter.doFilterInternal(get(AVAILABILITY_PATH, REMOTE_ADDR),
                    new MockHttpServletResponse(), new MockFilterChain());
        }
        var response = new MockHttpServletResponse();

        filter.doFilterInternal(get(BOOKABLE_DAYS_PATH, REMOTE_ADDR), response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(429);
    }

    // ── phase 387 audit (security MEDIUM) — span-weighted /bookable-days charge ──────────────────────

    private static final String FROM = "2026-11-01";

    private static MockHttpServletRequest bookableDays(String from, String to, String... serviceIds) {
        var req = get(BOOKABLE_DAYS_PATH, REMOTE_ADDR);
        if (from != null) {
            req.addParameter("from", from);
        }
        if (to != null) {
            req.addParameter("to", to);
        }
        if (serviceIds.length > 0) {
            req.addParameter("serviceId", serviceIds);
        }
        return req;
    }

    /** Forwarded requests before the first 429 (the bucket refills intervally, so this is deterministic). */
    private static int allowedBeforeThrottle(AuthRateLimitFilter filter, MockHttpServletRequest request)
            throws Exception {
        int allowed = 0;
        for (int i = 0; i < REQUESTS_TO_FIRE; i++) {
            var response = new MockHttpServletResponse();
            filter.doFilterInternal(request, response, new MockFilterChain());
            if (response.getStatus() == 429) {
                return allowed;
            }
            allowed++;
        }
        return allowed;
    }

    @Test
    @DisplayName("should_chargeSpanWeightedTokens_when_bookableDaysWideWindow (phase 387 audit)")
    void should_chargeSpanWeightedTokens_when_bookableDaysWideWindow() throws Exception {
        // 61-day window (the whole guest window), one service: 1 + 61/14 = 5 tokens -> 60/5 = 12 calls.
        // A flat 1-token charge would have allowed 60 calls = 3 660 dates of slot walk per minute.
        AuthRateLimitFilter filter = realFilter();
        var request = bookableDays(FROM, "2026-12-31", UUID.randomUUID().toString());

        int allowed = allowedBeforeThrottle(filter, request);

        assertThat(allowed)
                .as("a 61-day /bookable-days read must cost 5 tokens of the shared 60-token budget")
                .isEqualTo(12);
    }

    @Test
    @DisplayName("should_allowTwentyMonthViews_when_realGuestPagesSingleServiceMonths (phase 387 audit)")
    void should_allowTwentyMonthViews_when_realGuestPagesSingleServiceMonths() throws Exception {
        // Real-guest budget: a 31-day single-service month view costs 1 + 31/14 = 3 tokens -> 20 month views
        // per minute per IP. The website fetches one month per page turn, so this is far above human paging.
        AuthRateLimitFilter filter = realFilter();
        var request = bookableDays(FROM, "2026-12-01", UUID.randomUUID().toString());

        int allowed = allowedBeforeThrottle(filter, request);

        assertThat(allowed).isEqualTo(20);
    }

    @Test
    @DisplayName("should_chargeExtraTokenPerService_when_bookableDaysMultiService (phase 387 audit)")
    void should_chargeExtraTokenPerService_when_bookableDaysMultiService() throws Exception {
        // 7-day window (span term 0) with 3 services, two repeated + one comma-joined: 1 + 0 + 2 = 3 tokens.
        AuthRateLimitFilter filter = realFilter();
        var request = bookableDays(FROM, "2026-11-07",
                UUID.randomUUID().toString(), UUID.randomUUID() + "," + UUID.randomUUID());

        int allowed = allowedBeforeThrottle(filter, request);

        assertThat(allowed).isEqualTo(20);
    }

    @Test
    @DisplayName("should_chargeBaseCost_when_bookableDaysParamsMissingOrInverted (phase 387 audit)")
    void should_chargeBaseCost_when_bookableDaysParamsMissingOrInverted() {
        String service = UUID.randomUUID().toString();

        assertThat(AuthRateLimitFilter.bookableDaysTokenCost(bookableDays(null, null))).isEqualTo(1);
        assertThat(AuthRateLimitFilter.bookableDaysTokenCost(bookableDays("2026-12-31", FROM, service)))
                .as("an inverted range 400s in the service — charge the base cost")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("should_capCostBelowCapacity_when_bookableDaysOutOfRange (phase 387 audit)")
    void should_capCostBelowCapacity_when_bookableDaysOutOfRange() {
        String[] elevenServices = java.util.stream.Stream.generate(() -> UUID.randomUUID().toString())
                .limit(11).toArray(String[]::new);

        long cost = AuthRateLimitFilter.bookableDaysTokenCost(bookableDays(FROM, "2030-01-01", elevenServices));

        assertThat(cost)
                .as("span clamped to 63 days and services to 10: 1 + 4 + 9 — an unpayable charge would turn "
                        + "the controller's clean 400 into a 429")
                .isEqualTo(14);
    }

    @Test
    @DisplayName("should_chargeFullSpan_when_bookableDaysDatesCarryOffsetSuffix (phase 387 audit cycle 2)")
    void should_chargeFullSpan_when_bookableDaysDatesCarryOffsetSuffix() {
        // The controller binds with @DateTimeFormat(iso = ISO.DATE) = DateTimeFormatter.ISO_DATE, which
        // accepts an offset suffix and walks the real window. The filter must price it exactly like the
        // unsuffixed request: a 31-day window = 1 + 31/14 = 3 tokens. A 31-day window is used on purpose:
        // the old ISO_LOCAL_DATE fail-open parse charged 1 and an ISO_LOCAL_DATE fail-closed parse would
        // charge 5 — both are distinguishable from the correct 3.
        String service = UUID.randomUUID().toString();
        long plainCost = AuthRateLimitFilter.bookableDaysTokenCost(bookableDays(FROM, "2026-12-01", service));
        var zulu = bookableDays(FROM + "Z", "2026-12-01Z", service);
        var encodedOffset = MockMvcRequestBuilders
                .get(URI.create(BOOKABLE_DAYS_PATH + "?from=2026-11-01%2B02:00&to=2026-12-01%2B02:00"
                        + "&serviceId=" + service))
                .buildRequest(new MockServletContext());

        long zuluCost = AuthRateLimitFilter.bookableDaysTokenCost(zulu);
        long encodedCost = AuthRateLimitFilter.bookableDaysTokenCost(encodedOffset);

        assertThat(encodedOffset.getParameter("from")).isEqualTo("2026-11-01+02:00");
        assertThat(plainCost).isEqualTo(3);
        assertThat(zuluCost).as("Z-suffixed 31-day window").isEqualTo(plainCost);
        assertThat(encodedCost).as("%2B02:00-suffixed 31-day window").isEqualTo(plainCost);
    }

    @Test
    @DisplayName("should_chargeMaxSpan_when_bookableDaysDateUnparsable (phase 387 audit cycle 2)")
    void should_chargeMaxSpan_when_bookableDaysDateUnparsable() {
        // Fail CLOSED: a present-but-unparsable from/to is charged the max span (63 days -> 1 + 63/14 = 5),
        // so any future drift between the filter's parser and the binder over-charges, never under-charges.
        String service = UUID.randomUUID().toString();

        long badFrom = AuthRateLimitFilter.bookableDaysTokenCost(bookableDays("not-a-date", "2026-12-31", service));
        long badTo = AuthRateLimitFilter.bookableDaysTokenCost(bookableDays(FROM, "2026-13-45", service));
        long badFromMissingTo = AuthRateLimitFilter.bookableDaysTokenCost(bookableDays("x", null, service));

        assertThat(badFrom).isEqualTo(5);
        assertThat(badTo).isEqualTo(5);
        assertThat(badFromMissingTo).isEqualTo(5);
    }

    @Test
    @DisplayName("should_chargeMaxSpan_when_bookableDaysDatePadded (phase 387 audit cycle 3)")
    void should_chargeMaxSpan_when_bookableDaysDatePadded() {
        // The Spring binder does not trim, so a padded date is a 400 at the controller; the filter must not
        // trim either and charges the fail-closed max span (5). A 2-day window is used on purpose: a trimming
        // parse would charge only 1 + 2/14 = 1 token.
        String service = UUID.randomUUID().toString();

        long leadingPad = AuthRateLimitFilter.bookableDaysTokenCost(bookableDays(" " + FROM, "2026-11-02", service));
        long trailingPad = AuthRateLimitFilter.bookableDaysTokenCost(bookableDays(FROM, "2026-11-02 ", service));
        long unpadded = AuthRateLimitFilter.bookableDaysTokenCost(bookableDays(FROM, "2026-11-02", service));

        assertThat(unpadded).as("non-vacuous: the unpadded 2-day window is base cost").isEqualTo(1);
        assertThat(leadingPad).isEqualTo(5);
        assertThat(trailingPad).isEqualTo(5);
    }
}
