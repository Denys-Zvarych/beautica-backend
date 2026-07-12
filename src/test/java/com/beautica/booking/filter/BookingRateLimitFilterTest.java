package com.beautica.booking.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BandwidthBuilder;
import io.github.bucket4j.Bucket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dedicated unit test for {@link BookingRateLimitFilter}, driving the real filter through
 * {@code doFilterInternal} — no {@code @WebMvcTest} slice exercises this filter, since
 * {@code WebMvcTestSupport} deliberately short-circuits it via {@code shouldNotFilter() = true}
 * (controller slices are not the place to test rate limiting). This test therefore closes the
 * only gap in the filter's coverage: path-scoping, per-user bucket keying, the 429 envelope
 * shape, and the unauthenticated/non-UUID-details pass-through branches.
 *
 * <p>Buckets are built with a REAL Caffeine {@link LoadingCache} (not mocked) so the actual
 * Bucket4j consumption logic runs — mirrors the pattern in
 * {@code GuestBookingPostRateLimitRegressionTest}, which drives {@code AuthRateLimitFilter} the
 * same way.
 */
@DisplayName("BookingRateLimitFilter — path-scoping, per-user keying, 429 envelope")
class BookingRateLimitFilterTest {

    private static final String CREATE_PATH = "/api/v1/bookings";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @AfterEach
    void clearSecurityContext() {
        // Every test that authenticates must not leak its principal into the next test.
        SecurityContextHolder.clearContext();
    }

    /** A single-slot bucket cache — the FIRST consume per key succeeds, every subsequent one 429s. */
    private static LoadingCache<String, Bucket> singleSlotBuckets() {
        return Caffeine.newBuilder().build(key -> Bucket.builder()
                .addLimit(bandwidth(1))
                .build());
    }

    private static Bandwidth bandwidth(long capacity) {
        return BandwidthBuilder.builder()
                .capacity(capacity)
                .refillIntervally(capacity, Duration.ofMinutes(1))
                .build();
    }

    private BookingRateLimitFilter filterWith(LoadingCache<String, Bucket> buckets) {
        return new BookingRateLimitFilter(buckets, OBJECT_MAPPER);
    }

    private static void authenticateAs(UUID userId) {
        var auth = new UsernamePasswordAuthenticationToken(
                "client@example.com", "N/A", List.of(new SimpleGrantedAuthority("ROLE_CLIENT")));
        auth.setDetails(userId); // mirrors JwtAuthenticationFilter.setDetails(userId)
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private static MockHttpServletRequest postCreate() {
        return new MockHttpServletRequest("POST", CREATE_PATH);
    }

    private static MockHttpServletRequest patchReschedule(UUID bookingId) {
        return new MockHttpServletRequest("PATCH", "/api/v1/bookings/" + bookingId + "/reschedule");
    }

    @Test
    @DisplayName("should_return429_when_sameUserExceedsCapacityOnCreatePath")
    void should_return429_when_sameUserExceedsCapacityOnCreatePath() throws Exception {
        BookingRateLimitFilter filter = filterWith(singleSlotBuckets());
        authenticateAs(UUID.randomUUID());

        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        MockFilterChain firstChain = new MockFilterChain();
        filter.doFilterInternal(postCreate(), firstResponse, firstChain);
        assertThat(firstChain.getRequest())
                .as("the first request within capacity must be forwarded")
                .isNotNull();
        assertThat(firstResponse.getStatus()).isNotEqualTo(429);

        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        MockFilterChain secondChain = new MockFilterChain();
        filter.doFilterInternal(postCreate(), secondResponse, secondChain);

        assertThat(secondResponse.getStatus())
                .as("a second POST /bookings from the SAME user within the window must be throttled")
                .isEqualTo(429);
        assertThat(secondChain.getRequest())
                .as("a throttled request must NOT be forwarded down the filter chain")
                .isNull();
    }

    @Test
    @DisplayName("should_return429_when_sameUserExceedsCapacityOnReschedulePath")
    void should_return429_when_sameUserExceedsCapacityOnReschedulePath() throws Exception {
        BookingRateLimitFilter filter = filterWith(singleSlotBuckets());
        UUID userId = UUID.randomUUID();
        authenticateAs(userId);

        filter.doFilterInternal(patchReschedule(UUID.randomUUID()), new MockHttpServletResponse(), new MockFilterChain());

        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        MockFilterChain secondChain = new MockFilterChain();
        filter.doFilterInternal(patchReschedule(UUID.randomUUID()), secondResponse, secondChain);

        assertThat(secondResponse.getStatus())
                .as("reschedule shares the SAME bucket as create — a second reschedule must also be throttled")
                .isEqualTo(429);
        assertThat(secondChain.getRequest()).isNull();
    }

    @Test
    @DisplayName("should_shareOneBucket_when_sameUserAlternatesCreateAndReschedule")
    void should_shareOneBucket_when_sameUserAlternatesCreateAndReschedule() throws Exception {
        // POST /bookings and PATCH .../reschedule are documented as sharing ONE bucket per
        // user — proves capacity consumed on create is visible on the very next reschedule call.
        BookingRateLimitFilter filter = filterWith(singleSlotBuckets());
        authenticateAs(UUID.randomUUID());

        filter.doFilterInternal(postCreate(), new MockHttpServletResponse(), new MockFilterChain());

        MockHttpServletResponse rescheduleResponse = new MockHttpServletResponse();
        filter.doFilterInternal(patchReschedule(UUID.randomUUID()), rescheduleResponse, new MockFilterChain());

        assertThat(rescheduleResponse.getStatus())
                .as("the single shared bucket must already be exhausted by the earlier create call")
                .isEqualTo(429);
    }

    @Test
    @DisplayName("should_keyBucketPerUser_when_twoDifferentUsersCallTheSamePath")
    void should_keyBucketPerUser_when_twoDifferentUsersCallTheSamePath() throws Exception {
        LoadingCache<String, Bucket> buckets = singleSlotBuckets();
        BookingRateLimitFilter filter = filterWith(buckets);

        UUID userA = UUID.randomUUID();
        authenticateAs(userA);
        filter.doFilterInternal(postCreate(), new MockHttpServletResponse(), new MockFilterChain());
        // Exhaust user A's bucket.
        MockHttpServletResponse userASecondResponse = new MockHttpServletResponse();
        filter.doFilterInternal(postCreate(), userASecondResponse, new MockFilterChain());
        assertThat(userASecondResponse.getStatus()).isEqualTo(429);

        // A DIFFERENT user's first request must succeed — buckets are keyed per user id,
        // not globally.
        SecurityContextHolder.clearContext();
        UUID userB = UUID.randomUUID();
        authenticateAs(userB);
        MockHttpServletResponse userBResponse = new MockHttpServletResponse();
        MockFilterChain userBChain = new MockFilterChain();
        filter.doFilterInternal(postCreate(), userBResponse, userBChain);

        assertThat(userBResponse.getStatus())
                .as("user B must not be throttled by user A's exhausted bucket")
                .isNotEqualTo(429);
        assertThat(userBChain.getRequest())
                .as("user B's first request must be forwarded")
                .isNotNull();
    }

    @Test
    @DisplayName("should_passThrough_when_pathIsNotABookingWriteEndpoint")
    void should_passThrough_when_pathIsNotABookingWriteEndpoint() throws Exception {
        BookingRateLimitFilter filter = filterWith(singleSlotBuckets());
        authenticateAs(UUID.randomUUID());
        UUID bookingId = UUID.randomUUID();

        // GET /bookings/me is a read, not a throttled write.
        var getMine = new MockHttpServletRequest("GET", "/api/v1/bookings/me");
        var getResponse = new MockHttpServletResponse();
        var getChain = new MockFilterChain();
        filter.doFilterInternal(getMine, getResponse, getChain);
        assertThat(getResponse.getStatus()).isNotEqualTo(429);
        assertThat(getChain.getRequest()).isNotNull();

        // PATCH .../confirm is a different booking-write endpoint, NOT reschedule — must not
        // consume (or be blocked by) the reschedule/create bucket.
        var patchConfirm = new MockHttpServletRequest("PATCH", "/api/v1/bookings/" + bookingId + "/confirm");
        var confirmResponse = new MockHttpServletResponse();
        var confirmChain = new MockFilterChain();
        filter.doFilterInternal(patchConfirm, confirmResponse, confirmChain);
        assertThat(confirmResponse.getStatus())
                .as("PATCH .../confirm must not be throttled by the create/reschedule bucket")
                .isNotEqualTo(429);
        assertThat(confirmChain.getRequest()).isNotNull();

        // The single-slot bucket must still be intact for a real create/reschedule call —
        // proves the two calls above never touched it.
        var createResponse = new MockHttpServletResponse();
        var createChain = new MockFilterChain();
        filter.doFilterInternal(postCreate(), createResponse, createChain);
        assertThat(createResponse.getStatus())
                .as("the booking-write bucket must be untouched by the non-matching paths above")
                .isNotEqualTo(429);
        assertThat(createChain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("should_passThrough_when_callerIsUnauthenticated")
    void should_passThrough_when_callerIsUnauthenticated() throws Exception {
        // No SecurityContextHolder authentication set at all.
        BookingRateLimitFilter filter = filterWith(singleSlotBuckets());

        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        MockFilterChain firstChain = new MockFilterChain();
        filter.doFilterInternal(postCreate(), firstResponse, firstChain);
        assertThat(firstResponse.getStatus()).isNotEqualTo(429);
        assertThat(firstChain.getRequest()).isNotNull();

        // A second unauthenticated call must ALSO pass through — there is no user id to key a
        // bucket on, so this filter never throttles it (the downstream 401 handles rejection).
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        MockFilterChain secondChain = new MockFilterChain();
        filter.doFilterInternal(postCreate(), secondResponse, secondChain);
        assertThat(secondResponse.getStatus())
                .as("unauthenticated booking-write requests are never throttled by this filter")
                .isNotEqualTo(429);
        assertThat(secondChain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("should_passThrough_when_authenticationDetailsIsNotAUuid")
    void should_passThrough_when_authenticationDetailsIsNotAUuid() throws Exception {
        // Defensive branch (Anti-Bug §B): getDetails() holding something other than a UUID
        // (e.g. a stale/legacy Authentication) must not be raw-cast — the filter passes
        // through instead of throwing a ClassCastException.
        BookingRateLimitFilter filter = filterWith(singleSlotBuckets());
        var auth = new UsernamePasswordAuthenticationToken(
                "client@example.com", "N/A", List.of(new SimpleGrantedAuthority("ROLE_CLIENT")));
        auth.setDetails("not-a-uuid");
        SecurityContextHolder.getContext().setAuthentication(auth);

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilterInternal(postCreate(), response, chain);

        assertThat(response.getStatus()).isNotEqualTo(429);
        assertThat(chain.getRequest())
                .as("a non-UUID authentication details value must not throw and must pass through")
                .isNotNull();
    }

    @Test
    @DisplayName("should_writeStandardApiResponseEnvelopeAndRetryAfterHeader_when_throttled")
    void should_writeStandardApiResponseEnvelopeAndRetryAfterHeader_when_throttled() throws Exception {
        BookingRateLimitFilter filter = filterWith(singleSlotBuckets());
        authenticateAs(UUID.randomUUID());

        filter.doFilterInternal(postCreate(), new MockHttpServletResponse(), new MockFilterChain());

        MockHttpServletResponse throttled = new MockHttpServletResponse();
        filter.doFilterInternal(postCreate(), throttled, new MockFilterChain());

        assertThat(throttled.getStatus()).isEqualTo(429);
        assertThat(throttled.getContentType()).isEqualTo("application/json");
        assertThat(throttled.getHeader("Retry-After")).isEqualTo("10");
        assertThat(throttled.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");

        JsonNode body = OBJECT_MAPPER.readTree(throttled.getContentAsByteArray());
        assertThat(body.path("success").asBoolean())
                .as("the 429 body must use the standard ApiResponse envelope, not an ad-hoc shape")
                .isFalse();
        assertThat(body.path("data").isNull())
                .as("data must be null on the 429 envelope")
                .isTrue();
        assertThat(body.path("message").asText())
                .isEqualTo("Too many requests — please slow down");
    }
}
