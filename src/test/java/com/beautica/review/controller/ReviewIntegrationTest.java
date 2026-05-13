package com.beautica.review.controller;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.common.ApiResponse;
import com.beautica.common.PageResponse;
import com.beautica.config.TestSecurityConfig;
import com.beautica.notification.service.NotificationOutboxService;
import com.beautica.review.dto.ReviewResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.impl.classic.HttpClients;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestSecurityConfig.class)
@DisplayName("Review — full-flow integration")
class ReviewIntegrationTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ReviewIntegrationTest.class);
    private static final String REVIEWS_URL   = "/api/v1/reviews";
    private static final String MASTERS_URL   = "/api/v1/masters";
    private static final String TEST_PASSWORD = "password123";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockBean
    private NotificationOutboxService notificationOutboxService;

    @BeforeEach
    void configureHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
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

    // ── fixtures ───────────────────────────────────────────────────────────────

    /**
     * Inserts a user with role INDEPENDENT_MASTER and a matching masters row.
     * No salon_id — INDEPENDENT_MASTERs have no salon affiliation.
     */
    private UUID createIndependentMaster(String email) {
        String hash   = passwordEncoder.encode(TEST_PASSWORD);
        UUID userId   = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active) VALUES (?, ?, ?, 'INDEPENDENT_MASTER', true)",
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
                "(id, owner_type, owner_id, name, base_duration_minutes, base_price, " +
                "buffer_minutes_after, is_active, created_at, updated_at) " +
                "VALUES (?, 'INDEPENDENT_MASTER', ?, 'Test Service', 60, 500.00, 0, true, NOW(), NOW())",
                serviceDefId, userId);

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

    private String createClientAndGetToken(String email) throws Exception {
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active) VALUES (?, ?, ?, 'CLIENT', true)",
                UUID.randomUUID(), email, passwordEncoder.encode(TEST_PASSWORD));
        return loginAndGetToken(email);
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

    private ResponseEntity<String> postReview(String token, UUID bookingId, int rating) {
        String body = """
                {"bookingId":"%s","rating":%d}
                """.formatted(bookingId, rating);
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
}
