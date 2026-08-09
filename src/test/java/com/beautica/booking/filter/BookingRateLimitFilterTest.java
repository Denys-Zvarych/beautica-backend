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
import java.time.LocalDate;
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
 *
 * <p>Also covers the decline/not-complete bucket (SEC MEDIUM finding — those two SMS/notification-
 * triggering endpoints previously had no rate limit at all). Every test in that section builds
 * the filter directly against a hand-built, small-capacity {@link LoadingCache} — never through
 * {@code RateLimitConfig} — so the {@code application-test.yml} override of
 * {@code booking-decline-capacity} (raised for unrelated ITs that call decline/not-complete
 * several times) cannot neuter this coverage.
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

    /**
     * Builds a filter with the given create/reschedule bucket and UNRELATED, generously-sized
     * decline/schedule-override buckets — for tests that only exercise the create/reschedule path
     * and must not be affected by either sibling bucket's capacity at all.
     */
    private BookingRateLimitFilter filterWith(LoadingCache<String, Bucket> writeBuckets) {
        return new BookingRateLimitFilter(writeBuckets, generousBuckets(), generousBuckets(), OBJECT_MAPPER);
    }

    /**
     * Builds a filter with the given decline/not-complete bucket and UNRELATED, generously-sized
     * create/reschedule and schedule-override buckets — the mirror of
     * {@link #filterWith(LoadingCache)} for tests that only exercise the decline/not-complete path.
     */
    private BookingRateLimitFilter filterWithDeclineBuckets(LoadingCache<String, Bucket> declineBuckets) {
        return new BookingRateLimitFilter(generousBuckets(), declineBuckets, generousBuckets(), OBJECT_MAPPER);
    }

    /**
     * Builds a filter with the given schedule-override-write bucket and UNRELATED, generously-sized
     * create/reschedule and decline buckets — the mirror of {@link #filterWith(LoadingCache)} for
     * tests that only exercise {@code PUT /masters/{masterId}/overrides/{date}}.
     */
    private BookingRateLimitFilter filterWithOverrideBuckets(LoadingCache<String, Bucket> overrideBuckets) {
        return new BookingRateLimitFilter(generousBuckets(), generousBuckets(), overrideBuckets, OBJECT_MAPPER);
    }

    /** A bucket cache with effectively unlimited capacity — for the "other" bucket in a test. */
    private static LoadingCache<String, Bucket> generousBuckets() {
        return Caffeine.newBuilder().build(key -> Bucket.builder()
                .addLimit(bandwidth(100_000))
                .build());
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

    private static MockHttpServletRequest patchDecline(UUID bookingId) {
        return new MockHttpServletRequest("PATCH", "/api/v1/bookings/" + bookingId + "/decline");
    }

    private static MockHttpServletRequest patchNotComplete(UUID bookingId) {
        return new MockHttpServletRequest("PATCH", "/api/v1/bookings/" + bookingId + "/not-complete");
    }

    private static MockHttpServletRequest patchBookingCancel(UUID bookingId) {
        return new MockHttpServletRequest("PATCH", "/api/v1/bookings/" + bookingId + "/cancel");
    }

    private static MockHttpServletRequest patchAppointmentCancel(UUID appointmentId) {
        return new MockHttpServletRequest("PATCH", "/api/v1/appointments/" + appointmentId + "/cancel");
    }

    private static MockHttpServletRequest patchAppointmentDecline(UUID appointmentId) {
        return new MockHttpServletRequest("PATCH", "/api/v1/appointments/" + appointmentId + "/decline");
    }

    private static MockHttpServletRequest patchAppointmentServiceDecline(UUID appointmentId, UUID bookingId) {
        return new MockHttpServletRequest(
                "PATCH", "/api/v1/appointments/" + appointmentId + "/services/" + bookingId + "/decline");
    }

    private static MockHttpServletRequest patchAppointmentComplete(UUID appointmentId) {
        return new MockHttpServletRequest("PATCH", "/api/v1/appointments/" + appointmentId + "/complete");
    }

    private static MockHttpServletRequest patchAppointmentNotComplete(UUID appointmentId) {
        return new MockHttpServletRequest("PATCH", "/api/v1/appointments/" + appointmentId + "/not-complete");
    }

    private static MockHttpServletRequest patchAppointmentReschedule(UUID appointmentId) {
        return new MockHttpServletRequest("PATCH", "/api/v1/appointments/" + appointmentId + "/reschedule");
    }

    /** Phase 30.5 — the per-item reschedule route. */
    private static MockHttpServletRequest patchAppointmentServiceReschedule(UUID appointmentId, UUID bookingId) {
        return new MockHttpServletRequest(
                "PATCH", "/api/v1/appointments/" + appointmentId + "/services/" + bookingId + "/reschedule");
    }

    /** Phase 30.6 — the per-item cancel route. */
    private static MockHttpServletRequest patchAppointmentServiceCancel(UUID appointmentId, UUID bookingId) {
        return new MockHttpServletRequest(
                "PATCH", "/api/v1/appointments/" + appointmentId + "/services/" + bookingId + "/cancel");
    }

    /** No verb suffix at all — must pass through unthrottled (guards against an over-broad prefix match). */
    private static MockHttpServletRequest patchAppointmentServiceNoVerb(UUID appointmentId, UUID bookingId) {
        return new MockHttpServletRequest(
                "PATCH", "/api/v1/appointments/" + appointmentId + "/services/" + bookingId);
    }

    private static MockHttpServletRequest putScheduleOverride(UUID masterId, LocalDate date) {
        return new MockHttpServletRequest(
                "PUT", "/api/v1/masters/" + masterId + "/overrides/" + date);
    }

    private static MockHttpServletRequest postOverrideConflictsPreview(UUID masterId) {
        return new MockHttpServletRequest(
                "POST", "/api/v1/masters/" + masterId + "/overrides/conflicts");
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

    // -------------------------------------------------------------------------------------------
    // decline / not-complete — the SMS/notification-triggering endpoints (SEC MEDIUM finding).
    // These share a SEPARATE bucket from create/reschedule (different threat model — see the
    // class javadoc): a mass-SMS/smishing burst, not advisory-lock connection-pool exhaustion.
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("should_throttle_repeatedDeclineCalls_fromSameProvider")
    void should_throttle_repeatedDeclineCalls_fromSameProvider() throws Exception {
        BookingRateLimitFilter filter = filterWithDeclineBuckets(singleSlotBuckets());
        authenticateAs(UUID.randomUUID());

        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        MockFilterChain firstChain = new MockFilterChain();
        filter.doFilterInternal(patchDecline(UUID.randomUUID()), firstResponse, firstChain);
        assertThat(firstResponse.getStatus())
                .as("the first decline within capacity must be forwarded")
                .isNotEqualTo(429);
        assertThat(firstChain.getRequest()).isNotNull();

        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        MockFilterChain secondChain = new MockFilterChain();
        filter.doFilterInternal(patchDecline(UUID.randomUUID()), secondResponse, secondChain);

        assertThat(secondResponse.getStatus())
                .as("a second PATCH .../decline from the SAME provider within the window must be throttled — "
                        + "this closes the mass-SMS-burst gap the endpoint previously had no rate limit at all")
                .isEqualTo(429);
        assertThat(secondChain.getRequest())
                .as("a throttled decline must NOT be forwarded down the filter chain (no SMS dispatch downstream)")
                .isNull();
    }

    @Test
    @DisplayName("should_throttle_repeatedNotCompleteCalls_fromSameProvider")
    void should_throttle_repeatedNotCompleteCalls_fromSameProvider() throws Exception {
        BookingRateLimitFilter filter = filterWithDeclineBuckets(singleSlotBuckets());
        authenticateAs(UUID.randomUUID());

        filter.doFilterInternal(patchNotComplete(UUID.randomUUID()), new MockHttpServletResponse(), new MockFilterChain());

        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        MockFilterChain secondChain = new MockFilterChain();
        filter.doFilterInternal(patchNotComplete(UUID.randomUUID()), secondResponse, secondChain);

        assertThat(secondResponse.getStatus())
                .as("a second PATCH .../not-complete from the SAME provider within the window must be throttled")
                .isEqualTo(429);
        assertThat(secondChain.getRequest()).isNull();
    }

    @Test
    @DisplayName("should_shareOneBucket_when_sameProviderAlternatesDeclineAndNotComplete")
    void should_shareOneBucket_when_sameProviderAlternatesDeclineAndNotComplete() throws Exception {
        BookingRateLimitFilter filter = filterWithDeclineBuckets(singleSlotBuckets());
        authenticateAs(UUID.randomUUID());

        filter.doFilterInternal(patchDecline(UUID.randomUUID()), new MockHttpServletResponse(), new MockFilterChain());

        MockHttpServletResponse notCompleteResponse = new MockHttpServletResponse();
        filter.doFilterInternal(patchNotComplete(UUID.randomUUID()), notCompleteResponse, new MockFilterChain());

        assertThat(notCompleteResponse.getStatus())
                .as("decline and not-complete are documented as sharing ONE bucket per provider")
                .isEqualTo(429);
    }

    @Test
    @DisplayName("should_useSeparateBucket_when_declineAndCreateRescheduleAreBothCalledBySameUser")
    void should_useSeparateBucket_when_declineAndCreateRescheduleAreBothCalledBySameUser() throws Exception {
        // Exhausting the create/reschedule bucket must NOT throttle a decline call, and
        // vice-versa — the two buckets guard independent threat models and must not share budget.
        LoadingCache<String, Bucket> writeBuckets = singleSlotBuckets();
        LoadingCache<String, Bucket> declineBuckets = singleSlotBuckets();
        BookingRateLimitFilter filter =
                new BookingRateLimitFilter(writeBuckets, declineBuckets, generousBuckets(), OBJECT_MAPPER);
        authenticateAs(UUID.randomUUID());

        // Exhaust the create/reschedule bucket.
        filter.doFilterInternal(postCreate(), new MockHttpServletResponse(), new MockFilterChain());
        MockHttpServletResponse createExhausted = new MockHttpServletResponse();
        filter.doFilterInternal(postCreate(), createExhausted, new MockFilterChain());
        assertThat(createExhausted.getStatus()).isEqualTo(429);

        // The decline bucket is untouched — the first decline call must still succeed.
        MockHttpServletResponse declineResponse = new MockHttpServletResponse();
        MockFilterChain declineChain = new MockFilterChain();
        filter.doFilterInternal(patchDecline(UUID.randomUUID()), declineResponse, declineChain);
        assertThat(declineResponse.getStatus())
                .as("an exhausted create/reschedule bucket must not throttle decline — separate bucket")
                .isNotEqualTo(429);
        assertThat(declineChain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("should_writeSixtySecondRetryAfter_when_declineThrottled")
    void should_writeSixtySecondRetryAfter_when_declineThrottled() throws Exception {
        // The decline/not-complete bucket has its OWN 60s window — distinct from the
        // create/reschedule bucket's 10s Retry-After.
        BookingRateLimitFilter filter = filterWithDeclineBuckets(singleSlotBuckets());
        authenticateAs(UUID.randomUUID());

        filter.doFilterInternal(patchDecline(UUID.randomUUID()), new MockHttpServletResponse(), new MockFilterChain());

        MockHttpServletResponse throttled = new MockHttpServletResponse();
        filter.doFilterInternal(patchDecline(UUID.randomUUID()), throttled, new MockFilterChain());

        assertThat(throttled.getStatus()).isEqualTo(429);
        assertThat(throttled.getHeader("Retry-After")).isEqualTo("60");
    }

    @Test
    @DisplayName("should_passThrough_when_declineOrNotCompleteCallerIsUnauthenticated")
    void should_passThrough_when_declineOrNotCompleteCallerIsUnauthenticated() throws Exception {
        BookingRateLimitFilter filter = filterWithDeclineBuckets(singleSlotBuckets());

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilterInternal(patchDecline(UUID.randomUUID()), response, chain);

        assertThat(response.getStatus()).isNotEqualTo(429);
        assertThat(chain.getRequest())
                .as("there is no user id to key a bucket on — the downstream @PreAuthorize rejects instead")
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

        // PATCH .../complete is a different booking-write endpoint, NOT reschedule — must not
        // consume (or be blocked by) the reschedule/create bucket. (Track 24.x retired PATCH
        // .../confirm entirely — bookings are auto-confirmed at creation — so /complete is now
        // the real unthrottled booking-write route used to prove this path-matcher behaviour.)
        var patchComplete = new MockHttpServletRequest("PATCH", "/api/v1/bookings/" + bookingId + "/complete");
        var completeResponse = new MockHttpServletResponse();
        var completeChain = new MockFilterChain();
        filter.doFilterInternal(patchComplete, completeResponse, completeChain);
        assertThat(completeResponse.getStatus())
                .as("PATCH .../complete must not be throttled by the create/reschedule bucket")
                .isNotEqualTo(429);
        assertThat(completeChain.getRequest()).isNotNull();

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

    // -------------------------------------------------------------------------------------------
    // SEC MEDIUM finding: PATCH /bookings/{id}/cancel and the whole
    // /appointments/{appointmentId}/* PATCH mutation family were entirely unthrottled. Each route
    // below must now land in the bucket documented in the class Javadoc.
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("should_return429_when_sameClientExceedsCapacityOnBookingCancelPath")
    void should_return429_when_sameClientExceedsCapacityOnBookingCancelPath() throws Exception {
        BookingRateLimitFilter filter = filterWith(singleSlotBuckets());
        authenticateAs(UUID.randomUUID());

        filter.doFilterInternal(patchBookingCancel(UUID.randomUUID()), new MockHttpServletResponse(), new MockFilterChain());

        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        MockFilterChain secondChain = new MockFilterChain();
        filter.doFilterInternal(patchBookingCancel(UUID.randomUUID()), secondResponse, secondChain);

        assertThat(secondResponse.getStatus())
                .as("PATCH /bookings/{id}/cancel must now be throttled — it previously had no bucket at all")
                .isEqualTo(429);
        assertThat(secondChain.getRequest()).isNull();
    }

    @Test
    @DisplayName("should_shareCreateRescheduleBucket_when_sameClientAlternatesCreateAndBookingCancel")
    void should_shareCreateRescheduleBucket_when_sameClientAlternatesCreateAndBookingCancel() throws Exception {
        // PATCH /bookings/{id}/cancel is documented as sharing the SAME bucket as create/reschedule
        // (row-lock-contention threat model, not the SMS/decline threat model).
        BookingRateLimitFilter filter = filterWith(singleSlotBuckets());
        authenticateAs(UUID.randomUUID());

        filter.doFilterInternal(postCreate(), new MockHttpServletResponse(), new MockFilterChain());

        MockHttpServletResponse cancelResponse = new MockHttpServletResponse();
        filter.doFilterInternal(patchBookingCancel(UUID.randomUUID()), cancelResponse, new MockFilterChain());

        assertThat(cancelResponse.getStatus())
                .as("the single shared bucket must already be exhausted by the earlier create call")
                .isEqualTo(429);
    }

    @Test
    @DisplayName("should_return429_when_sameClientExceedsCapacityOnAppointmentCancelPath")
    void should_return429_when_sameClientExceedsCapacityOnAppointmentCancelPath() throws Exception {
        BookingRateLimitFilter filter = filterWith(singleSlotBuckets());
        authenticateAs(UUID.randomUUID());

        filter.doFilterInternal(patchAppointmentCancel(UUID.randomUUID()), new MockHttpServletResponse(), new MockFilterChain());

        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        MockFilterChain secondChain = new MockFilterChain();
        filter.doFilterInternal(patchAppointmentCancel(UUID.randomUUID()), secondResponse, secondChain);

        assertThat(secondResponse.getStatus())
                .as("PATCH /appointments/{id}/cancel must now be throttled on the write bucket")
                .isEqualTo(429);
        assertThat(secondChain.getRequest()).isNull();
    }

    @Test
    @DisplayName("should_return429_when_sameProviderExceedsCapacityOnAppointmentCompletePath")
    void should_return429_when_sameProviderExceedsCapacityOnAppointmentCompletePath() throws Exception {
        BookingRateLimitFilter filter = filterWith(singleSlotBuckets());
        authenticateAs(UUID.randomUUID());

        filter.doFilterInternal(patchAppointmentComplete(UUID.randomUUID()), new MockHttpServletResponse(), new MockFilterChain());

        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        MockFilterChain secondChain = new MockFilterChain();
        filter.doFilterInternal(patchAppointmentComplete(UUID.randomUUID()), secondResponse, secondChain);

        assertThat(secondResponse.getStatus())
                .as("PATCH /appointments/{id}/complete must now be throttled on the write bucket — unlike "
                        + "the single-booking /bookings/{id}/complete, it takes a real appointments-header lock")
                .isEqualTo(429);
        assertThat(secondChain.getRequest()).isNull();
    }

    @Test
    @DisplayName("should_return429_when_sameCallerExceedsCapacityOnAppointmentReschedulePath")
    void should_return429_when_sameCallerExceedsCapacityOnAppointmentReschedulePath() throws Exception {
        BookingRateLimitFilter filter = filterWith(singleSlotBuckets());
        authenticateAs(UUID.randomUUID());

        filter.doFilterInternal(patchAppointmentReschedule(UUID.randomUUID()), new MockHttpServletResponse(), new MockFilterChain());

        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        MockFilterChain secondChain = new MockFilterChain();
        filter.doFilterInternal(patchAppointmentReschedule(UUID.randomUUID()), secondResponse, secondChain);

        assertThat(secondResponse.getStatus())
                .as("PATCH /appointments/{id}/reschedule must now be throttled on the write bucket")
                .isEqualTo(429);
        assertThat(secondChain.getRequest()).isNull();
    }

    @Test
    @DisplayName("should_return429_when_sameProviderExceedsCapacityOnAppointmentDeclinePath")
    void should_return429_when_sameProviderExceedsCapacityOnAppointmentDeclinePath() throws Exception {
        BookingRateLimitFilter filter = filterWithDeclineBuckets(singleSlotBuckets());
        authenticateAs(UUID.randomUUID());

        filter.doFilterInternal(patchAppointmentDecline(UUID.randomUUID()), new MockHttpServletResponse(), new MockFilterChain());

        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        MockFilterChain secondChain = new MockFilterChain();
        filter.doFilterInternal(patchAppointmentDecline(UUID.randomUUID()), secondResponse, secondChain);

        assertThat(secondResponse.getStatus())
                .as("PATCH /appointments/{id}/decline must now be throttled on the decline bucket — it "
                        + "previously had no bucket at all")
                .isEqualTo(429);
        assertThat(secondChain.getRequest()).isNull();
    }

    @Test
    @DisplayName("should_return429_when_sameProviderExceedsCapacityOnAppointmentNotCompletePath")
    void should_return429_when_sameProviderExceedsCapacityOnAppointmentNotCompletePath() throws Exception {
        BookingRateLimitFilter filter = filterWithDeclineBuckets(singleSlotBuckets());
        authenticateAs(UUID.randomUUID());

        filter.doFilterInternal(patchAppointmentNotComplete(UUID.randomUUID()), new MockHttpServletResponse(), new MockFilterChain());

        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        MockFilterChain secondChain = new MockFilterChain();
        filter.doFilterInternal(patchAppointmentNotComplete(UUID.randomUUID()), secondResponse, secondChain);

        assertThat(secondResponse.getStatus())
                .as("PATCH /appointments/{id}/not-complete must now be throttled on the decline bucket")
                .isEqualTo(429);
        assertThat(secondChain.getRequest()).isNull();
    }

    @Test
    @DisplayName("should_return429_when_sameProviderExceedsCapacityOnAppointmentServiceDeclinePath")
    void should_return429_when_sameProviderExceedsCapacityOnAppointmentServiceDeclinePath() throws Exception {
        // The per-service decline suffix (.../services/{bookingId}/decline) has TWO path segments
        // after the appointment id — proves the matcher does not require an exact segment count and
        // still routes this shape onto the decline bucket rather than falling through unmatched.
        BookingRateLimitFilter filter = filterWithDeclineBuckets(singleSlotBuckets());
        authenticateAs(UUID.randomUUID());
        UUID appointmentId = UUID.randomUUID();

        filter.doFilterInternal(
                patchAppointmentServiceDecline(appointmentId, UUID.randomUUID()),
                new MockHttpServletResponse(), new MockFilterChain());

        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        MockFilterChain secondChain = new MockFilterChain();
        filter.doFilterInternal(
                patchAppointmentServiceDecline(appointmentId, UUID.randomUUID()), secondResponse, secondChain);

        assertThat(secondResponse.getStatus())
                .as("PATCH .../services/{bookingId}/decline must now be throttled on the decline bucket")
                .isEqualTo(429);
        assertThat(secondChain.getRequest()).isNull();
    }

    @Test
    @DisplayName("should_shareOneDeclineBucket_when_sameProviderAlternatesWholeVisitAndPerServiceDecline")
    void should_shareOneDeclineBucket_when_sameProviderAlternatesWholeVisitAndPerServiceDecline() throws Exception {
        // Proves the two-segment .../services/{bookingId}/decline suffix is NOT mis-bucketed into a
        // separate/unmatched route: it must consume from the EXACT SAME per-provider bucket as the
        // one-segment whole-visit .../{appointmentId}/decline route.
        BookingRateLimitFilter filter = filterWithDeclineBuckets(singleSlotBuckets());
        authenticateAs(UUID.randomUUID());

        filter.doFilterInternal(patchAppointmentDecline(UUID.randomUUID()), new MockHttpServletResponse(), new MockFilterChain());

        MockHttpServletResponse serviceDeclineResponse = new MockHttpServletResponse();
        filter.doFilterInternal(
                patchAppointmentServiceDecline(UUID.randomUUID(), UUID.randomUUID()),
                serviceDeclineResponse, new MockFilterChain());

        assertThat(serviceDeclineResponse.getStatus())
                .as("the whole-visit decline call above must already have exhausted the shared decline bucket")
                .isEqualTo(429);
    }

    // -------------------------------------------------------------------------------------------
    // Track 30.x — per-item reschedule (30.5) and per-item cancel (30.6) routes. Zero production
    // code change was required (the filter's existing endsWith-based suffix matching already
    // covers both, exactly as it already covered per-service decline) — these tests are the
    // executable pin for that claim.
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("should_return429_when_sameCallerExceedsCapacityOnAppointmentServiceReschedulePath")
    void should_return429_when_sameCallerExceedsCapacityOnAppointmentServiceReschedulePath() throws Exception {
        BookingRateLimitFilter filter = filterWith(singleSlotBuckets());
        authenticateAs(UUID.randomUUID());

        filter.doFilterInternal(
                patchAppointmentServiceReschedule(UUID.randomUUID(), UUID.randomUUID()),
                new MockHttpServletResponse(), new MockFilterChain());

        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        MockFilterChain secondChain = new MockFilterChain();
        filter.doFilterInternal(
                patchAppointmentServiceReschedule(UUID.randomUUID(), UUID.randomUUID()), secondResponse, secondChain);

        assertThat(secondResponse.getStatus())
                .as("PATCH .../services/{bookingId}/reschedule must be throttled on the write bucket")
                .isEqualTo(429);
        assertThat(secondResponse.getHeader("Retry-After")).isEqualTo("10");
        assertThat(secondChain.getRequest()).isNull();
    }

    @Test
    @DisplayName("should_return429_when_sameClientExceedsCapacityOnAppointmentServiceCancelPath")
    void should_return429_when_sameClientExceedsCapacityOnAppointmentServiceCancelPath() throws Exception {
        BookingRateLimitFilter filter = filterWith(singleSlotBuckets());
        authenticateAs(UUID.randomUUID());

        filter.doFilterInternal(
                patchAppointmentServiceCancel(UUID.randomUUID(), UUID.randomUUID()),
                new MockHttpServletResponse(), new MockFilterChain());

        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        MockFilterChain secondChain = new MockFilterChain();
        filter.doFilterInternal(
                patchAppointmentServiceCancel(UUID.randomUUID(), UUID.randomUUID()), secondResponse, secondChain);

        assertThat(secondResponse.getStatus())
                .as("PATCH .../services/{bookingId}/cancel must be throttled on the write bucket")
                .isEqualTo(429);
        assertThat(secondResponse.getHeader("Retry-After")).isEqualTo("10");
        assertThat(secondChain.getRequest()).isNull();
    }

    @Test
    @DisplayName("should_shareOneWriteBucket_when_sameCallerAlternatesWholeVisitAndPerItemReschedule")
    void should_shareOneWriteBucket_when_sameCallerAlternatesWholeVisitAndPerItemReschedule() throws Exception {
        BookingRateLimitFilter filter = filterWith(singleSlotBuckets());
        authenticateAs(UUID.randomUUID());

        filter.doFilterInternal(patchAppointmentReschedule(UUID.randomUUID()), new MockHttpServletResponse(), new MockFilterChain());

        MockHttpServletResponse perItemResponse = new MockHttpServletResponse();
        filter.doFilterInternal(
                patchAppointmentServiceReschedule(UUID.randomUUID(), UUID.randomUUID()),
                perItemResponse, new MockFilterChain());

        assertThat(perItemResponse.getStatus())
                .as("the whole-visit reschedule call above must already have exhausted the shared write bucket — "
                        + "a caller cannot evade the budget by alternating whole-visit and per-item calls")
                .isEqualTo(429);
    }

    @Test
    @DisplayName("should_shareOneWriteBucket_when_sameClientAlternatesPerItemCancelAndPerItemReschedule")
    void should_shareOneWriteBucket_when_sameClientAlternatesPerItemCancelAndPerItemReschedule() throws Exception {
        BookingRateLimitFilter filter = filterWith(singleSlotBuckets());
        authenticateAs(UUID.randomUUID());

        filter.doFilterInternal(
                patchAppointmentServiceCancel(UUID.randomUUID(), UUID.randomUUID()),
                new MockHttpServletResponse(), new MockFilterChain());

        MockHttpServletResponse rescheduleResponse = new MockHttpServletResponse();
        filter.doFilterInternal(
                patchAppointmentServiceReschedule(UUID.randomUUID(), UUID.randomUUID()),
                rescheduleResponse, new MockFilterChain());

        assertThat(rescheduleResponse.getStatus())
                .as("both per-item write verbs must draw on the SAME shared write bucket")
                .isEqualTo(429);
    }

    @Test
    @DisplayName("should_notThrottle_when_pathEndsWithServicesSegmentOnly")
    void should_notThrottle_when_pathEndsWithServicesSegmentOnly() throws Exception {
        // Guards against a future over-broad prefix match: a path that ends with the bare
        // /services/{bookingId} segment (no /decline, /reschedule, or /cancel verb suffix) must
        // pass through unthrottled — selectRoute returns null for it.
        BookingRateLimitFilter filter = filterWith(singleSlotBuckets());
        authenticateAs(UUID.randomUUID());
        UUID appointmentId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();

        MockHttpServletResponse first = new MockHttpServletResponse();
        MockFilterChain firstChain = new MockFilterChain();
        filter.doFilterInternal(patchAppointmentServiceNoVerb(appointmentId, bookingId), first, firstChain);
        MockHttpServletResponse second = new MockHttpServletResponse();
        MockFilterChain secondChain = new MockFilterChain();
        filter.doFilterInternal(patchAppointmentServiceNoVerb(appointmentId, bookingId), second, secondChain);

        assertThat(second.getStatus()).isNotEqualTo(429);
        assertThat(secondChain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("should_passThrough_when_bookingCompleteCalled_stillDeliberatelyUnthrottled")
    void should_passThrough_when_bookingCompleteCalled_stillDeliberatelyUnthrottled() throws Exception {
        // Regression guard: PATCH /bookings/{id}/complete must remain the one deliberately
        // unthrottled booking-write route (see class Javadoc) even after widening this filter to
        // cover cancel and the /appointments/** family.
        BookingRateLimitFilter filter = filterWith(singleSlotBuckets());
        authenticateAs(UUID.randomUUID());
        UUID bookingId = UUID.randomUUID();

        var first = new MockHttpServletResponse();
        var firstChain = new MockFilterChain();
        filter.doFilterInternal(new MockHttpServletRequest("PATCH", "/api/v1/bookings/" + bookingId + "/complete"), first, firstChain);
        var second = new MockHttpServletResponse();
        var secondChain = new MockFilterChain();
        filter.doFilterInternal(new MockHttpServletRequest("PATCH", "/api/v1/bookings/" + bookingId + "/complete"), second, secondChain);

        assertThat(second.getStatus()).isNotEqualTo(429);
        assertThat(secondChain.getRequest()).isNotNull();
    }

    // -------------------------------------------------------------------------------------------
    // PUT /masters/{masterId}/overrides/{date} — its OWN dedicated bucket (2026-07-26 product
    // decision reversal, D6). This route used to share the decline bucket (plus a proportional
    // per-conflict charge) because an override write could fan a provider-authored note out to
    // guest phones as SMS — that vector no longer exists (an override-driven decline carries no
    // note and dispatches no notification at all), so it is now throttled purely as an ordinary
    // destructive bulk write, sized for its own fan-out (the mobile client expands a multi-day
    // save into one PUT per date — see RateLimitConfig#scheduleOverrideWriteCapacity's javadoc).
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("should_return429_when_sameActorExceedsCapacityOnScheduleOverridePutPath")
    void should_return429_when_sameActorExceedsCapacityOnScheduleOverridePutPath() throws Exception {
        BookingRateLimitFilter filter = filterWithOverrideBuckets(singleSlotBuckets());
        authenticateAs(UUID.randomUUID());
        UUID masterId = UUID.randomUUID();
        LocalDate date = LocalDate.now().plusDays(1);

        filter.doFilterInternal(putScheduleOverride(masterId, date), new MockHttpServletResponse(), new MockFilterChain());

        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        MockFilterChain secondChain = new MockFilterChain();
        filter.doFilterInternal(putScheduleOverride(masterId, date), secondResponse, secondChain);

        assertThat(secondResponse.getStatus())
                .as("PUT /masters/{id}/overrides/{date} must be throttled on its own bucket")
                .isEqualTo(429);
        assertThat(secondChain.getRequest()).isNull();
    }

    @Test
    @DisplayName("should_notShareBucket_when_declineAndScheduleOverrideAreBothCalledBySameActor")
    void should_notShareBucket_when_declineAndScheduleOverrideAreBothCalledBySameActor() throws Exception {
        // The two routes used to share ONE bucket (pre-D6); now they must NOT — exhausting either
        // must never throttle the other.
        LoadingCache<String, Bucket> declineBuckets = singleSlotBuckets();
        LoadingCache<String, Bucket> overrideBuckets = singleSlotBuckets();
        BookingRateLimitFilter filter =
                new BookingRateLimitFilter(generousBuckets(), declineBuckets, overrideBuckets, OBJECT_MAPPER);
        authenticateAs(UUID.randomUUID());

        // Exhaust the decline bucket.
        filter.doFilterInternal(patchDecline(UUID.randomUUID()), new MockHttpServletResponse(), new MockFilterChain());
        MockHttpServletResponse declineExhausted = new MockHttpServletResponse();
        filter.doFilterInternal(patchDecline(UUID.randomUUID()), declineExhausted, new MockFilterChain());
        assertThat(declineExhausted.getStatus()).isEqualTo(429);

        // The override-write bucket is untouched — its first call must still succeed.
        MockHttpServletResponse overrideResponse = new MockHttpServletResponse();
        MockFilterChain overrideChain = new MockFilterChain();
        filter.doFilterInternal(
                putScheduleOverride(UUID.randomUUID(), LocalDate.now().plusDays(1)), overrideResponse, overrideChain);
        assertThat(overrideResponse.getStatus())
                .as("an exhausted decline bucket must not throttle the override write — separate bucket")
                .isNotEqualTo(429);
        assertThat(overrideChain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("should_allowThirtyOneConsecutivePuts_when_actorSavesAMonthLongVacation")
    void should_allowThirtyOneConsecutivePuts_when_actorSavesAMonthLongVacation() throws Exception {
        // Mirrors RateLimitConfig's documented default capacity (50) for scheduleOverrideWriteBuckets:
        // the mobile client expands a multi-day save into one PUT per date, so a realistic month-long
        // vacation is ~31 consecutive requests from one actor — this must never trip the bucket.
        long capacity = 50L;
        LoadingCache<String, Bucket> overrideBuckets =
                Caffeine.newBuilder().build(key -> Bucket.builder().addLimit(bandwidth(capacity)).build());
        BookingRateLimitFilter filter = filterWithOverrideBuckets(overrideBuckets);
        UUID actorId = UUID.randomUUID();
        authenticateAs(actorId);
        UUID masterId = UUID.randomUUID();
        LocalDate firstDay = LocalDate.now().plusDays(1);

        for (int i = 0; i < 31; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            filter.doFilterInternal(putScheduleOverride(masterId, firstDay.plusDays(i)), response, chain);

            assertThat(response.getStatus())
                    .as("PUT #%d of a 31-day vacation span must never be throttled by the production-"
                            + "sized override bucket", i + 1)
                    .isNotEqualTo(429);
            assertThat(chain.getRequest()).isNotNull();
        }
    }

    @Test
    @DisplayName("should_passThrough_when_overridePreviewCalled_readOnlyRouteNotCovered")
    void should_passThrough_when_overridePreviewCalled_readOnlyRouteNotCovered() throws Exception {
        // The read-only POST .../overrides/conflicts preview never declines anything and must not
        // be mis-matched by the PUT-only override-write route check.
        BookingRateLimitFilter filter = filterWithOverrideBuckets(singleSlotBuckets());
        authenticateAs(UUID.randomUUID());
        UUID masterId = UUID.randomUUID();

        filter.doFilterInternal(postOverrideConflictsPreview(masterId), new MockHttpServletResponse(), new MockFilterChain());

        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        MockFilterChain secondChain = new MockFilterChain();
        filter.doFilterInternal(postOverrideConflictsPreview(masterId), secondResponse, secondChain);

        assertThat(secondResponse.getStatus())
                .as("the read-only preview must never be throttled — it is not a decline-triggering write")
                .isNotEqualTo(429);
        assertThat(secondChain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("should_writeSixtySecondRetryAfter_when_scheduleOverrideThrottled")
    void should_writeSixtySecondRetryAfter_when_scheduleOverrideThrottled() throws Exception {
        // Security finding 2: any 429 this route can still emit must carry Retry-After — this
        // filter's writeTooManyRequests already sets it for every route uniformly, this just pins
        // the override bucket's own 60s value.
        BookingRateLimitFilter filter = filterWithOverrideBuckets(singleSlotBuckets());
        authenticateAs(UUID.randomUUID());
        UUID masterId = UUID.randomUUID();
        LocalDate date = LocalDate.now().plusDays(1);

        filter.doFilterInternal(putScheduleOverride(masterId, date), new MockHttpServletResponse(), new MockFilterChain());

        MockHttpServletResponse throttled = new MockHttpServletResponse();
        filter.doFilterInternal(putScheduleOverride(masterId, date), throttled, new MockFilterChain());

        assertThat(throttled.getStatus()).isEqualTo(429);
        assertThat(throttled.getHeader("Retry-After")).isEqualTo("60");
    }

    @Test
    @DisplayName("should_passThrough_when_scheduleOverrideCallerIsUnauthenticated")
    void should_passThrough_when_scheduleOverrideCallerIsUnauthenticated() throws Exception {
        BookingRateLimitFilter filter = filterWithOverrideBuckets(singleSlotBuckets());

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilterInternal(
                putScheduleOverride(UUID.randomUUID(), LocalDate.now().plusDays(1)), response, chain);

        assertThat(response.getStatus()).isNotEqualTo(429);
        assertThat(chain.getRequest())
                .as("there is no user id to key a bucket on — the downstream @PreAuthorize rejects instead")
                .isNotNull();
    }

    @Test
    @DisplayName("should_passThrough_when_appointmentPathIsAReadNotACoveredMutation")
    void should_passThrough_when_appointmentPathIsAReadNotACoveredMutation() throws Exception {
        BookingRateLimitFilter filter = filterWith(singleSlotBuckets());
        authenticateAs(UUID.randomUUID());

        var getResponse = new MockHttpServletResponse();
        var getChain = new MockFilterChain();
        filter.doFilterInternal(
                new MockHttpServletRequest("GET", "/api/v1/appointments/" + UUID.randomUUID()), getResponse, getChain);

        assertThat(getResponse.getStatus()).isNotEqualTo(429);
        assertThat(getChain.getRequest()).isNotNull();
    }
}
