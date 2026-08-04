package com.beautica.review.controller;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.common.ApiResponse;
import com.beautica.common.PageResponse;
import com.beautica.common.RatingBucket;
import com.beautica.config.TestSecurityConfig;
import com.beautica.notification.service.NotificationOutboxService;
import com.beautica.review.dto.CreateReviewRequest;
import com.beautica.review.dto.MasterReviewSummaryResponse;
import com.beautica.review.dto.ReviewResponse;
import com.beautica.review.dto.SalonReviewSummaryResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

@Import(TestSecurityConfig.class)
@DisplayName("Review — full-flow integration")
class ReviewIntegrationTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ReviewIntegrationTest.class);
    private static final String REVIEWS_URL   = "/api/v1/reviews";
    private static final String MASTERS_URL   = "/api/v1/masters";
    private static final String TEST_PASSWORD = "Str0ngP@ss1!";
    private static final HttpComponentsClientHttpRequestFactory HTTP_FACTORY =
            new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault());

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockBean
    private NotificationOutboxService notificationOutboxService;

    @AfterAll
    static void destroyHttpFactory() throws Exception {
        HTTP_FACTORY.destroy();
    }

    @BeforeEach
    void configureHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(HTTP_FACTORY);
    }

    // ── tests ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /reviews — 201 + master avgRating=5.00 and reviewCount=1 after first review")
    void should_createReviewAndUpdateMasterRating_when_completedBooking() throws Exception {
        UUID masterId        = createIndependentMaster("im-rate-" + System.nanoTime() + "@beautica.test");
        UUID masterServiceId = createIndependentMasterService(masterId);
        String clientEmail   = "cli-rate-" + System.nanoTime() + "@beautica.test";
        String clientToken   = createClientAndGetToken(clientEmail);
        UUID clientId        = resolveUserIdByEmail(clientEmail);
        UUID bookingId       = createCompletedBooking(clientId, masterId, masterServiceId);

        log.debug("Act: POST {} rating=5 for bookingId={}", REVIEWS_URL, bookingId);
        ResponseEntity<String> createResp = postReview(clientToken, bookingId, 5);

        assertThat(createResp.getStatusCode())
                .as("first review must return 201")
                .isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> masterResp = restTemplate.getForEntity(
                MASTERS_URL + "/" + masterId, String.class);
        assertThat(masterResp.getStatusCode())
                .as("GET /masters/{masterId} must return 200")
                .isEqualTo(HttpStatus.OK);

        var data = objectMapper.readTree(masterResp.getBody()).path("data");
        String avgRatingText = data.path("avgRating").asText();
        int reviewCount      = data.path("reviewCount").asInt();

        assertThat(new java.math.BigDecimal(avgRatingText))
                .as("avgRating must be 5.00 after a single rating-5 review")
                .isEqualByComparingTo("5.00");
        assertThat(reviewCount)
                .as("reviewCount must be 1 after a single review")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("POST /reviews — avgRating becomes 3.00 after two reviews with ratings 4 and 2")
    void should_updateAverageRating_when_secondReviewAdded() throws Exception {
        UUID masterId        = createIndependentMaster("im-avg-" + System.nanoTime() + "@beautica.test");
        UUID masterServiceId = createIndependentMasterService(masterId);
        String clientEmail   = "cli-avg-" + System.nanoTime() + "@beautica.test";
        String clientToken   = createClientAndGetToken(clientEmail);
        UUID clientId        = resolveUserIdByEmail(clientEmail);

        UUID bookingId1 = createCompletedBooking(clientId, masterId, masterServiceId);
        UUID bookingId2 = createCompletedBooking(clientId, masterId, masterServiceId);

        log.debug("Act: POST {} rating=4 for bookingId1={}", REVIEWS_URL, bookingId1);
        assertThat(postReview(clientToken, bookingId1, 4).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        log.debug("Act: POST {} rating=2 for bookingId2={}", REVIEWS_URL, bookingId2);
        assertThat(postReview(clientToken, bookingId2, 2).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        var data = objectMapper.readTree(
                restTemplate.getForEntity(MASTERS_URL + "/" + masterId, String.class).getBody()
        ).path("data");

        assertThat(new java.math.BigDecimal(data.path("avgRating").asText()))
                .as("avgRating must be 3.00 — average of 4 and 2")
                .isEqualByComparingTo("3.00");
        assertThat(data.path("reviewCount").asInt())
                .as("reviewCount must be 2 after two reviews")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("POST /reviews — 201 when the booking is CONFIRMED but its endsAt has already "
            + "elapsed — the bug fix: a booking the provider never closed but that aged into the "
            + "client's Past tab by time must be reviewable")
    void should_createReview_when_confirmedBookingHasElapsed() throws Exception {
        UUID masterId        = createIndependentMaster("im-elapsed-" + System.nanoTime() + "@beautica.test");
        UUID masterServiceId = createIndependentMasterService(masterId);
        String clientEmail   = "cli-elapsed-" + System.nanoTime() + "@beautica.test";
        String clientToken   = createClientAndGetToken(clientEmail);
        UUID clientId        = resolveUserIdByEmail(clientEmail);
        UUID bookingId       = createElapsedConfirmedBooking(clientId, masterId, masterServiceId);

        log.debug("Act: POST {} rating=5 for elapsed CONFIRMED bookingId={}", REVIEWS_URL, bookingId);
        ResponseEntity<String> createResp = postReview(clientToken, bookingId, 5);

        assertThat(createResp.getStatusCode())
                .as("an elapsed-but-unclosed CONFIRMED booking must be reviewable — 201 — body: %s",
                        createResp.getBody())
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("POST /reviews — 400 when the booking is CONFIRMED and its endsAt is still in the "
            + "FUTURE — the important assertion the review-eligibility widening must preserve")
    void should_return400_when_confirmedBookingHasNotElapsed() throws Exception {
        UUID masterId        = createIndependentMaster("im-future-" + System.nanoTime() + "@beautica.test");
        UUID masterServiceId = createIndependentMasterService(masterId);
        String clientEmail   = "cli-future-" + System.nanoTime() + "@beautica.test";
        String clientToken   = createClientAndGetToken(clientEmail);
        UUID clientId        = resolveUserIdByEmail(clientEmail);
        UUID bookingId       = createFutureConfirmedBooking(clientId, masterId, masterServiceId);

        log.debug("Act: POST {} rating=5 for not-yet-elapsed CONFIRMED bookingId={}", REVIEWS_URL, bookingId);
        ResponseEntity<String> resp = postReview(clientToken, bookingId, 5);

        assertThat(resp.getStatusCode())
                .as("a CONFIRMED booking whose window has not elapsed must stay unreviewable — 400 — "
                        + "body: %s", resp.getBody())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("POST /reviews — 409 when the same booking is reviewed a second time")
    void should_return409_when_duplicateReviewSubmitted() throws Exception {
        UUID masterId        = createIndependentMaster("im-dup-" + System.nanoTime() + "@beautica.test");
        UUID masterServiceId = createIndependentMasterService(masterId);
        String clientEmail   = "cli-dup-" + System.nanoTime() + "@beautica.test";
        String clientToken   = createClientAndGetToken(clientEmail);
        UUID clientId        = resolveUserIdByEmail(clientEmail);
        UUID bookingId       = createCompletedBooking(clientId, masterId, masterServiceId);

        log.debug("Act: first POST {} must succeed, bookingId={}", REVIEWS_URL, bookingId);
        assertThat(postReview(clientToken, bookingId, 5).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        log.debug("Act: second POST {} same bookingId={} — must return 409", REVIEWS_URL, bookingId);
        ResponseEntity<String> duplicate = postReview(clientToken, bookingId, 5);

        assertThat(duplicate.getStatusCode())
                .as("duplicate review for the same booking must return 409")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("GET /masters/{masterId}/reviews — two reviews returned in descending createdAt order")
    void should_returnReviewsInDescendingOrder_when_multipleReviewsExist() throws Exception {
        UUID masterId        = createIndependentMaster("im-ord-" + System.nanoTime() + "@beautica.test");
        UUID masterServiceId = createIndependentMasterService(masterId);
        String clientEmail   = "cli-ord-" + System.nanoTime() + "@beautica.test";
        String clientToken   = createClientAndGetToken(clientEmail);
        UUID clientId        = resolveUserIdByEmail(clientEmail);

        UUID bookingId1 = createCompletedBooking(clientId, masterId, masterServiceId);
        UUID bookingId2 = createCompletedBooking(clientId, masterId, masterServiceId);

        assertThat(postReview(clientToken, bookingId1, 4).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(postReview(clientToken, bookingId2, 3).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> listResp = restTemplate.getForEntity(
                MASTERS_URL + "/" + masterId + "/reviews", String.class);
        assertThat(listResp.getStatusCode())
                .as("GET /masters/{masterId}/reviews must return 200")
                .isEqualTo(HttpStatus.OK);

        var wrapper = objectMapper.readValue(
                listResp.getBody(),
                new TypeReference<ApiResponse<PageResponse<ReviewResponse>>>() {});

        assertThat(wrapper.success()).isTrue();
        assertThat(wrapper.data().data())
                .as("response must contain exactly 2 reviews")
                .hasSize(2);

        OffsetDateTime first  = wrapper.data().data().get(0).createdAt();
        OffsetDateTime second = wrapper.data().data().get(1).createdAt();
        assertThat(first)
                .as("first review must not be earlier than second — list must be in DESC createdAt order")
                .isAfterOrEqualTo(second);
    }

    @Test
    @DisplayName("GET /masters/{masterId}/reviews — serviceName is hydrated from the real DB for every row (widened JOIN FETCH regression guard)")
    void should_returnServiceNameFromRealDb_when_listingMasterReviews() throws Exception {
        UUID masterId        = createIndependentMaster("im-svcname-" + System.nanoTime() + "@beautica.test");
        UUID masterServiceId = createIndependentMasterService(masterId);
        String clientEmail   = "cli-svcname-" + System.nanoTime() + "@beautica.test";
        String clientToken   = createClientAndGetToken(clientEmail);
        UUID clientId        = resolveUserIdByEmail(clientEmail);
        UUID bookingId       = createCompletedBooking(clientId, masterId, masterServiceId);

        log.debug("Act: POST {} rating=5 for bookingId={} then GET the master review list", REVIEWS_URL, bookingId);
        assertThat(postReview(clientToken, bookingId, 5).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> listResp = restTemplate.getForEntity(
                MASTERS_URL + "/" + masterId + "/reviews", String.class);
        assertThat(listResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        var wrapper = objectMapper.readValue(
                listResp.getBody(),
                new TypeReference<ApiResponse<PageResponse<ReviewResponse>>>() {});

        assertThat(wrapper.data().data())
                .as("serviceName must equal the seeded service_definitions.name — proves findByIdsWithGraph's "
                        + "booking.masterService.serviceDefinition JOIN FETCH chain actually hydrates in a real query, "
                        + "not just against a mocked repository")
                .extracting(ReviewResponse::serviceName)
                .containsExactly("Test Service");
    }

    @Test
    @DisplayName("GET /reviews/{reviewId} — 200 with serviceName hydrated from the real DB (findByIdWithAssociations regression guard)")
    void should_returnServiceNameFromRealDb_when_gettingSingleReview() throws Exception {
        UUID masterId        = createIndependentMaster("im-single-" + System.nanoTime() + "@beautica.test");
        UUID masterServiceId = createIndependentMasterService(masterId);
        String clientEmail   = "cli-single-" + System.nanoTime() + "@beautica.test";
        String clientToken   = createClientAndGetToken(clientEmail);
        UUID clientId        = resolveUserIdByEmail(clientEmail);
        UUID bookingId       = createCompletedBooking(clientId, masterId, masterServiceId);

        ResponseEntity<String> createResp = postReview(clientToken, bookingId, 5);
        assertThat(createResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID reviewId = objectMapper.readValue(createResp.getBody(),
                new TypeReference<ApiResponse<ReviewResponse>>() {}).data().id();

        // This endpoint was rewritten from @EntityGraph to an explicit JOIN FETCH
        // (findByIdWithAssociations). open-in-view=false means a missing hop here throws
        // LazyInitializationException on a real request while every other test in this suite
        // (WebMvcTest with a mocked service, SpringBootTest with a mocked repository) stays green.
        log.debug("Act: GET {}/{} — single-review endpoint against the real Testcontainers-backed stack", REVIEWS_URL, reviewId);
        ResponseEntity<String> resp = restTemplate.getForEntity(REVIEWS_URL + "/" + reviewId, String.class);

        assertThat(resp.getStatusCode())
                .as("GET /reviews/{reviewId} must return 200 through the real DB, not just a mocked repository")
                .isEqualTo(HttpStatus.OK);

        ReviewResponse body = objectMapper.readValue(resp.getBody(),
                new TypeReference<ApiResponse<ReviewResponse>>() {}).data();

        assertThat(body.id()).isEqualTo(reviewId);
        assertThat(body.serviceName())
                .as("serviceName must be hydrated by findByIdWithAssociations' widened JOIN FETCH chain, "
                        + "matching the seeded service_definitions.name — not null and not a lazy-init failure")
                .isEqualTo("Test Service");
    }

    @Test
    @DisplayName("GET /reviews/{reviewId} — 404 against the real DB when the review id does not exist")
    void should_return404FromRealDb_when_reviewIdUnknown() throws Exception {
        UUID unknownReviewId = UUID.randomUUID();

        // findByIdWithAssociations' widened JOIN FETCH chain must not turn a genuinely missing
        // row into a 500 (e.g. an inner-join variant would still correctly return empty here,
        // but this pins the real end-to-end 404 contract through the rewritten query, not just
        // the mocked-repository path already covered by ReviewServiceTest).
        log.debug("Act: GET {}/{} — unknown reviewId against the real Testcontainers-backed stack", REVIEWS_URL, unknownReviewId);
        ResponseEntity<String> resp = restTemplate.getForEntity(REVIEWS_URL + "/" + unknownReviewId, String.class);

        assertThat(resp.getStatusCode())
                .as("an unknown reviewId must return 404 through the real DB-backed findByIdWithAssociations")
                .isEqualTo(HttpStatus.NOT_FOUND);

        var body = objectMapper.readTree(resp.getBody());
        assertThat(body.path("success").asBoolean())
                .as("the 404 must use the error envelope with success=false")
                .isFalse();
        assertThat(body.path("message").asText())
                .as("the 404 message must be the generic sentinel, never an internal detail")
                .isEqualTo("Resource not found");
    }

    @Test
    @DisplayName("GET /masters/{masterId}/reviews — review list reflects new review after cache eviction on POST")
    void should_returnNewReview_when_cacheEvictedAfterCreate() throws Exception {
        UUID masterId        = createIndependentMaster("im-cache-" + System.nanoTime() + "@beautica.test");
        UUID masterServiceId = createIndependentMasterService(masterId);
        String clientEmail   = "cli-cache-" + System.nanoTime() + "@beautica.test";
        String clientToken   = createClientAndGetToken(clientEmail);
        UUID clientId        = resolveUserIdByEmail(clientEmail);

        // Prime the cache with an empty list before any review exists.
        ResponseEntity<String> firstGet = restTemplate.getForEntity(
                MASTERS_URL + "/" + masterId + "/reviews", String.class);
        assertThat(firstGet.getStatusCode()).isEqualTo(HttpStatus.OK);
        var firstWrapper = objectMapper.readValue(
                firstGet.getBody(),
                new com.fasterxml.jackson.core.type.TypeReference<
                        com.beautica.common.ApiResponse<com.beautica.common.PageResponse<ReviewResponse>>>() {});
        assertThat(firstWrapper.data().data())
                .as("no reviews before POST — list must be empty")
                .isEmpty();

        // Create a booking and submit a review.
        UUID bookingId = createCompletedBooking(clientId, masterId, masterServiceId);
        log.debug("Act: POST {} rating=4 for bookingId={}", REVIEWS_URL, bookingId);
        assertThat(postReview(clientToken, bookingId, 4).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        // Second GET must return the new review — stale cache must have been evicted after commit.
        ResponseEntity<String> secondGet = restTemplate.getForEntity(
                MASTERS_URL + "/" + masterId + "/reviews", String.class);
        assertThat(secondGet.getStatusCode()).isEqualTo(HttpStatus.OK);
        var secondWrapper = objectMapper.readValue(
                secondGet.getBody(),
                new com.fasterxml.jackson.core.type.TypeReference<
                        com.beautica.common.ApiResponse<com.beautica.common.PageResponse<ReviewResponse>>>() {});
        assertThat(secondWrapper.data().data())
                .as("review list must contain the new review after cache was evicted post-commit")
                .hasSize(1);
        assertThat(secondWrapper.data().data().get(0).rating())
                .as("the persisted review rating must be 4")
                .isEqualTo(4);
    }

    // ── Master reviews summary (Phase 8.10) ─────────────────────────────────────

    @Test
    @DisplayName("GET /masters/{masterId}/reviews/summary — zero-filled 5→1 distribution and avg matches the mixed-rating fixture")
    void should_zeroFillMasterRatingDistributionAndMatchPersistedAverage_when_summaryRequested() throws Exception {
        UUID masterId        = createIndependentMaster("im-sum-" + System.nanoTime() + "@beautica.test");
        UUID masterServiceId = createIndependentMasterService(masterId);

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        createRatedMasterReview(masterId, masterServiceId, 5, now.minusHours(3), "Sum", "Aaa");
        createRatedMasterReview(masterId, masterServiceId, 3, now.minusHours(2), "Sum", "Bbb");
        createRatedMasterReview(masterId, masterServiceId, 1, now.minusHours(1), "Sum", "Ccc");

        log.debug("Act: GET {}/{}/reviews/summary with no Authorization header", MASTERS_URL, masterId);
        ResponseEntity<String> resp = restTemplate.getForEntity(
                MASTERS_URL + "/" + masterId + "/reviews/summary", String.class);

        assertThat(resp.getStatusCode())
                .as("master summary endpoint must be reachable with no auth")
                .isEqualTo(HttpStatus.OK);
        MasterReviewSummaryResponse summary = objectMapper.readValue(resp.getBody(),
                new TypeReference<ApiResponse<MasterReviewSummaryResponse>>() {}).data();

        assertThat(summary.reviewCount())
                .as("reviewCount must equal the 3 reviews submitted for this master")
                .isEqualTo(3);
        assertThat(summary.avgRating())
                .as("avgRating must be the persisted average of 5, 3 and 1")
                .isEqualByComparingTo("3.00");
        assertThat(summary.ratingDistribution())
                .as("distribution must always contain exactly 5 buckets (5-star down to 1-star), "
                        + "zero-filled for ratings with no reviews")
                .extracting(RatingBucket::rating,
                        RatingBucket::count)
                .containsExactly(
                        tuple(5, 1L),
                        tuple(4, 0L),
                        tuple(3, 1L),
                        tuple(2, 0L),
                        tuple(1, 1L)
                );
    }

    @Test
    @DisplayName("GET /masters/{masterId}/reviews/summary — avgRating null, count 0, all-zero distribution when the master has no reviews")
    void should_returnNullAvgAndAllZeroDistribution_when_masterHasNoReviews() throws Exception {
        UUID masterId = createIndependentMaster("im-zero-" + System.nanoTime() + "@beautica.test");

        log.debug("Act: GET {}/{}/reviews/summary for a master with zero reviews", MASTERS_URL, masterId);
        ResponseEntity<String> resp = restTemplate.getForEntity(
                MASTERS_URL + "/" + masterId + "/reviews/summary", String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        MasterReviewSummaryResponse summary = objectMapper.readValue(resp.getBody(),
                new TypeReference<ApiResponse<MasterReviewSummaryResponse>>() {}).data();

        assertThat(summary.reviewCount())
                .as("reviewCount must be 0 for a master with no reviews")
                .isEqualTo(0);
        assertThat(summary.avgRating())
                .as("avgRating must be null — never a fabricated 0.00 — when there are no reviews")
                .isNull();
        assertThat(summary.ratingDistribution())
                .as("distribution must still contain all 5 buckets, each zero-filled")
                .extracting(RatingBucket::rating,
                        RatingBucket::count)
                .containsExactly(
                        tuple(5, 0L),
                        tuple(4, 0L),
                        tuple(3, 0L),
                        tuple(2, 0L),
                        tuple(1, 0L)
                );
    }

    @Test
    @DisplayName("GET /masters/{masterId}/reviews/summary — 404 for an unknown masterId")
    void should_return404_when_masterSummaryRequestedForUnknownMaster() {
        UUID unknownMasterId = UUID.randomUUID();

        log.debug("Act: GET {}/{}/reviews/summary for an unknown master", MASTERS_URL, unknownMasterId);
        ResponseEntity<String> resp = restTemplate.getForEntity(
                MASTERS_URL + "/" + unknownMasterId + "/reviews/summary", String.class);

        assertThat(resp.getStatusCode())
                .as("an unknown masterId must return 404, not an empty 200 summary")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── Master reviews sort (Phase 8.11) ────────────────────────────────────────

    @Test
    @DisplayName("GET /masters/{masterId}/reviews — NEWEST/OLDEST/HIGHEST/LOWEST + default order correctly, with createdAt-DESC tie-break on equal ratings")
    void should_returnReviewsInEachSortOrder_when_masterReviewsHaveVariedRatingsAndTimestamps() throws Exception {
        UUID masterId        = createIndependentMaster("im-msort-" + System.nanoTime() + "@beautica.test");
        UUID masterServiceId = createIndependentMasterService(masterId);

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        // Two rating-3 reviews at different times exercise the createdAt-DESC tie-break.
        UUID r5old = createRatedMasterReview(masterId, masterServiceId, 5, now.minusHours(5), "A", "Aaa");
        UUID r3old = createRatedMasterReview(masterId, masterServiceId, 3, now.minusHours(4), "B", "Bbb");
        UUID r1mid = createRatedMasterReview(masterId, masterServiceId, 1, now.minusHours(3), "C", "Ccc");
        UUID r3new = createRatedMasterReview(masterId, masterServiceId, 3, now.minusHours(1), "D", "Ddd");

        // NEWEST — createdAt DESC.
        assertMasterReviewOrder(masterId, "NEWEST", List.of(r3new, r1mid, r3old, r5old));
        // OLDEST — createdAt ASC.
        assertMasterReviewOrder(masterId, "OLDEST", List.of(r5old, r3old, r1mid, r3new));
        // HIGHEST — rating DESC, createdAt DESC tie-break → r3new (-1h) before r3old (-4h).
        assertMasterReviewOrder(masterId, "HIGHEST", List.of(r5old, r3new, r3old, r1mid));
        // LOWEST — rating ASC, createdAt DESC tie-break → r3new before r3old.
        assertMasterReviewOrder(masterId, "LOWEST", List.of(r1mid, r3new, r3old, r5old));
        // Default (no sort param) must equal NEWEST — preserves pre-8.11 behaviour.
        assertMasterReviewOrder(masterId, null, List.of(r3new, r1mid, r3old, r5old));
    }

    @Test
    @DisplayName("GET /masters/{masterId}/reviews?sort=BOGUS — 400 with a redacted message that does not echo the invalid value")
    void should_return400_when_masterReviewsSortParamInvalid() throws Exception {
        UUID masterId = createIndependentMaster("im-badsort-" + System.nanoTime() + "@beautica.test");

        log.debug("Act: GET {}/{}/reviews?sort=BOGUS — invalid enum value", MASTERS_URL, masterId);
        ResponseEntity<String> resp = restTemplate.getForEntity(
                MASTERS_URL + "/" + masterId + "/reviews?sort=BOGUS", String.class);

        assertThat(resp.getStatusCode())
                .as("an unparseable sort enum value must return 400, never 500")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        var body = objectMapper.readTree(resp.getBody());
        assertThat(body.path("success").asBoolean())
                .as("the 400 must use the error envelope with success=false")
                .isFalse();
        assertThat(body.path("message").asText())
                .as("the 400 message must not echo the invalid value back to the caller")
                .doesNotContain("BOGUS");
    }

    @Test
    @DisplayName("GET /masters/{masterId}/reviews?page=10001 — 400 when the page number exceeds MAX_PAGE_NUMBER (shared guard, master route)")
    void should_return400_when_masterReviewsPageNumberExceedsMaximum() throws Exception {
        UUID masterId = createIndependentMaster("im-page-" + System.nanoTime() + "@beautica.test");

        log.debug("Act: GET {}/{}/reviews?page=10001 — page number one past MAX_PAGE_NUMBER", MASTERS_URL, masterId);
        ResponseEntity<String> resp = restTemplate.getForEntity(
                MASTERS_URL + "/" + masterId + "/reviews?page=10001", String.class);

        assertThat(resp.getStatusCode())
                .as("a page number past MAX_PAGE_NUMBER must return 400, never 500 or an OOM-risking deep scan")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        var body = objectMapper.readTree(resp.getBody());
        assertThat(body.path("success").asBoolean())
                .as("the 400 must use the error envelope with success=false")
                .isFalse();
    }

    @Test
    @DisplayName("GET /masters/{masterId}/reviews — client names are masked (First L.) on every sort branch; the full surname never leaks")
    void should_maskClientNamesOnEveryMasterSortBranch() throws Exception {
        UUID masterId        = createIndependentMaster("im-mask-" + System.nanoTime() + "@beautica.test");
        UUID masterServiceId = createIndependentMasterService(masterId);

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        createRatedMasterReview(masterId, masterServiceId, 5, now.minusHours(2), "Alpha", "Secretova");
        createRatedMasterReview(masterId, masterServiceId, 2, now.minusHours(1), "Beta", "Hiddenov");

        for (String sort : List.of("NEWEST", "OLDEST", "HIGHEST", "LOWEST")) {
            log.debug("Act: GET {}/{}/reviews?sort={} asserting masked client names", MASTERS_URL, masterId, sort);
            ResponseEntity<String> resp = restTemplate.getForEntity(
                    MASTERS_URL + "/" + masterId + "/reviews?sort=" + sort, String.class);
            assertThat(resp.getStatusCode())
                    .as("sort=%s must return 200", sort)
                    .isEqualTo(HttpStatus.OK);

            var wrapper = objectMapper.readValue(resp.getBody(),
                    new TypeReference<ApiResponse<PageResponse<ReviewResponse>>>() {});
            assertThat(wrapper.data().data())
                    .as("sort=%s must mask every client display name to 'First L.'", sort)
                    .extracting(ReviewResponse::clientDisplayName)
                    .containsExactlyInAnyOrder("Alpha S.", "Beta H.");
            assertThat(wrapper.data().data())
                    .as("sort=%s must never leak a full surname on any review", sort)
                    .allSatisfy(r -> assertThat(r.clientDisplayName())
                            .doesNotContain("Secretova")
                            .doesNotContain("Hiddenov"));
        }
    }

    @Test
    @DisplayName("GET /masters/{masterId}/reviews — a new review evicts ALL cached sort keys (NEWEST + HIGHEST both reflect it), proving prefix eviction spans every sort")
    void should_evictAllSortKeys_when_reviewCreatedForMaster() throws Exception {
        UUID masterId        = createIndependentMaster("im-evictall-" + System.nanoTime() + "@beautica.test");
        UUID masterServiceId = createIndependentMasterService(masterId);

        // Prime two distinct sort caches with an empty page before any review exists — each is a
        // separate cache key ("master:<id>:sort:NEWEST:..." vs "...:sort:HIGHEST:...").
        for (String sort : List.of("NEWEST", "HIGHEST")) {
            ResponseEntity<String> primeResp = restTemplate.getForEntity(
                    MASTERS_URL + "/" + masterId + "/reviews?sort=" + sort, String.class);
            assertThat(primeResp.getStatusCode()).isEqualTo(HttpStatus.OK);
            var primeWrapper = objectMapper.readValue(primeResp.getBody(),
                    new TypeReference<ApiResponse<PageResponse<ReviewResponse>>>() {});
            assertThat(primeWrapper.data().data())
                    .as("sort=%s must be empty before any review exists", sort)
                    .isEmpty();
        }

        String clientEmail = "cli-evictall-" + System.nanoTime() + "@beautica.test";
        String clientToken = createClientAndGetToken(clientEmail);
        UUID clientId      = resolveUserIdByEmail(clientEmail);
        UUID bookingId     = createCompletedBooking(clientId, masterId, masterServiceId);

        log.debug("Act: POST {} rating=4 for bookingId={} — must evict every primed sort key", REVIEWS_URL, bookingId);
        assertThat(postReview(clientToken, bookingId, 4).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        // The prefix scan on "master:<id>:" spans every sort key, so BOTH primed pages must now
        // reflect the new review — not just the default NEWEST key.
        for (String sort : List.of("NEWEST", "HIGHEST")) {
            ResponseEntity<String> resp = restTemplate.getForEntity(
                    MASTERS_URL + "/" + masterId + "/reviews?sort=" + sort, String.class);
            var wrapper = objectMapper.readValue(resp.getBody(),
                    new TypeReference<ApiResponse<PageResponse<ReviewResponse>>>() {});
            assertThat(wrapper.data().data())
                    .as("sort=%s cache must have been evicted post-commit — the new review must appear", sort)
                    .hasSize(1);
        }
    }

    // ── fixtures ───────────────────────────────────────────────────────────────

    /**
     * Inserts a user with role INDEPENDENT_MASTER and a matching masters row.
     * No salon_id — INDEPENDENT_MASTERs have no salon affiliation.
     */
    private UUID createIndependentMaster(String email) {
        String hash   = passwordEncoder.encode(TEST_PASSWORD);
        UUID userId   = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) VALUES (?, ?, ?, 'INDEPENDENT_MASTER', true, true)",
                userId, email, hash);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, master_type, avg_rating, review_count, is_active, created_at, updated_at) " +
                "VALUES (?, ?, 'INDEPENDENT_MASTER', 0.00, 0, true, NOW(), NOW())",
                masterId, userId);
        return masterId;
    }

    /**
     * Inserts a service_definition owned by the master's user (owner_type=INDEPENDENT_MASTER)
     * and a master_services row linking it to the master. Returns masterServiceId.
     */
    private UUID createIndependentMasterService(UUID masterId) {
        UUID userId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM masters WHERE id = ?", UUID.class, masterId);

        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions " +
                "(id, owner_type, owner_id, name, service_type_id, base_duration_minutes, base_price, " +
                "buffer_minutes_after, is_active, created_at, updated_at) " +
                "VALUES (?, 'INDEPENDENT_MASTER', ?, 'Test Service', ?, 60, 500.00, 0, true, NOW(), NOW())",
                serviceDefId, userId, resolveServiceTypeId());

        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) " +
                "VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId);

        return masterServiceId;
    }

    /**
     * Inserts a booking directly with status=COMPLETED.
     * starts_at/ends_at are set in the past so no overlap-exclusion constraint is triggered.
     * salon_id is omitted — INDEPENDENT_MASTER bookings have no salon (V18 schema, nullable).
     */
    private UUID createCompletedBooking(UUID clientId, UUID masterId, UUID masterServiceId) {
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings " +
                "(id, client_id, master_id, master_service_id, status, " +
                "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, " +
                "buffer_minutes_at_booking, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, 'COMPLETED', " +
                "NOW() - interval '2 hours', NOW() - interval '1 hour', " +
                "500.00, 60, 0, NOW(), NOW())",
                bookingId, clientId, masterId, masterServiceId);
        return bookingId;
    }

    /**
     * Inserts a booking directly with status=CONFIRMED whose {@code ends_at} has already elapsed —
     * the review-eligibility widening's CONFIRMED-and-elapsed disjunct. Mirrors {@link
     * #createCompletedBooking}'s past, non-overlapping window; only the status differs.
     */
    private UUID createElapsedConfirmedBooking(UUID clientId, UUID masterId, UUID masterServiceId) {
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings " +
                "(id, client_id, master_id, master_service_id, status, " +
                "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, " +
                "buffer_minutes_at_booking, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, 'CONFIRMED', " +
                "NOW() - interval '2 hours', NOW() - interval '1 hour', " +
                "500.00, 60, 0, NOW(), NOW())",
                bookingId, clientId, masterId, masterServiceId);
        return bookingId;
    }

    /**
     * Inserts a booking directly with status=CONFIRMED whose {@code ends_at} is still in the
     * future — the negative counterpart of {@link #createElapsedConfirmedBooking}, pinning that a
     * still-open booking stays unreviewable.
     */
    private UUID createFutureConfirmedBooking(UUID clientId, UUID masterId, UUID masterServiceId) {
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings " +
                "(id, client_id, master_id, master_service_id, status, " +
                "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, " +
                "buffer_minutes_at_booking, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, 'CONFIRMED', " +
                "NOW() + interval '1 hour', NOW() + interval '2 hours', " +
                "500.00, 60, 0, NOW(), NOW())",
                bookingId, clientId, masterId, masterServiceId);
        return bookingId;
    }

    private String createClientAndGetToken(String email) throws Exception {
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) VALUES (?, ?, ?, 'CLIENT', true, true)",
                UUID.randomUUID(), email, passwordEncoder.encode(TEST_PASSWORD));
        return loginAndGetToken(email);
    }

    private String createNamedClientAndGetToken(String email, String firstName, String lastName) throws Exception {
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, first_name, last_name, is_active, email_verified) " +
                "VALUES (?, ?, ?, 'CLIENT', ?, ?, true, true)",
                UUID.randomUUID(), email, passwordEncoder.encode(TEST_PASSWORD), firstName, lastName);
        return loginAndGetToken(email);
    }

    /**
     * Creates a fresh named client + independent-master completed booking, submits a review via the
     * real {@code POST /reviews} endpoint, then overrides {@code created_at} directly via SQL so
     * sort-order tests get deterministic, well-separated timestamps regardless of how fast
     * consecutive HTTP calls execute in CI. Returns the created review id. Mirrors
     * {@code SalonPublicProfileIntegrationTest#createRatedSalonReview}.
     */
    private UUID createRatedMasterReview(
            UUID masterId, UUID masterServiceId, int rating, OffsetDateTime createdAt,
            String firstName, String lastName) throws Exception {
        String clientEmail = "cli-mrated-" + System.nanoTime() + "@beautica.test";
        String clientToken = createNamedClientAndGetToken(clientEmail, firstName, lastName);
        UUID clientId      = resolveUserIdByEmail(clientEmail);
        UUID bookingId     = createCompletedBooking(clientId, masterId, masterServiceId);

        ResponseEntity<String> createResp = postReview(clientToken, bookingId, rating);
        assertThat(createResp.getStatusCode())
                .as("fixture master-review creation must succeed")
                .isEqualTo(HttpStatus.CREATED);
        UUID reviewId = objectMapper.readValue(createResp.getBody(),
                new TypeReference<ApiResponse<ReviewResponse>>() {}).data().id();

        jdbcTemplate.update("UPDATE reviews SET created_at = ? WHERE id = ?", createdAt, reviewId);
        return reviewId;
    }

    private void assertMasterReviewOrder(UUID masterId, String sort, List<UUID> expectedOrder) throws Exception {
        String url = MASTERS_URL + "/" + masterId + "/reviews" + (sort != null ? "?sort=" + sort : "");
        log.debug("Act: GET {} with no Authorization header", url);
        ResponseEntity<String> resp = restTemplate.getForEntity(url, String.class);
        assertThat(resp.getStatusCode())
                .as("sort=%s must return 200", sort)
                .isEqualTo(HttpStatus.OK);

        var wrapper = objectMapper.readValue(resp.getBody(),
                new TypeReference<ApiResponse<PageResponse<ReviewResponse>>>() {});
        assertThat(wrapper.data().data())
                .as("sort=%s must return the reviews in the expected order", sort)
                .extracting(ReviewResponse::id)
                .containsExactlyElementsOf(expectedOrder);
    }

    private String loginAndGetToken(String email) throws Exception {
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, TEST_PASSWORD), String.class);
        assertThat(resp.getStatusCode())
                .as("login must succeed for %s", email)
                .isEqualTo(HttpStatus.OK);
        var body = objectMapper.readValue(resp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        return body.data().accessToken();
    }

    /**
     * Resolves the UUID for a user by their exact email address.
     * Querying by email is race-condition safe regardless of test execution order.
     */
    private UUID resolveUserIdByEmail(String email) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE email = ?", UUID.class, email);
    }

    private ResponseEntity<String> postReview(String token, UUID bookingId, int rating) throws Exception {
        String body = objectMapper.writeValueAsString(new CreateReviewRequest(bookingId, rating, null));
        return restTemplate.exchange(
                REVIEWS_URL, HttpMethod.POST,
                new HttpEntity<>(body, bearerHeaders(token)),
                String.class);
    }

    private HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private UUID resolveServiceTypeId() {
        return jdbcTemplate.queryForObject(
                "SELECT st.id FROM service_types st "
                        + "JOIN platform_categories pc ON pc.name = st.platform_category_name "
                        + "WHERE st.is_active = TRUE AND pc.active = TRUE AND pc.status = 'APPROVED' "
                        + "ORDER BY st.name_uk LIMIT 1",
                UUID.class);
    }
}
