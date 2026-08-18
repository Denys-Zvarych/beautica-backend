package com.beautica.user;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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

import java.util.Iterator;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 27.6 (+27.x per-star distribution) — {@code GET /api/v1/users/me/rating}: the caller's own
 * aggregate client rating. Pins both the happy-path aggregate values AND the exact serialized-body
 * key set — a test that only round-trips through {@link UserRatingResponse} would not catch an
 * accidental extra field (e.g. a leaked comment or review list) the way asserting the raw JSON key
 * set does.
 */
@Import(TestSecurityConfig.class)
@DisplayName("GET /users/me/rating (Phase 27.6)")
class UserRatingIT extends AbstractIntegrationTest {

    private static final String URL = "/api/v1/users/me/rating";
    private static final String TEST_PASSWORD = "Str0ngP@ss1!";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void configureHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
    }

    @Test
    @DisplayName("200 with avgRating=null, reviewCount=0, and all 5 buckets zero-filled for a client who has never been reviewed")
    void should_returnNullAvgAndZeroCount_when_neverReviewed() throws Exception {
        String email = "rating-fresh-" + System.nanoTime() + "@beautica.test";
        createUser(email, "CLIENT");

        ResponseEntity<String> resp = restTemplate.exchange(
                URL, HttpMethod.GET, new HttpEntity<>(bearerHeaders(tokenFor(email))), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(resp.getBody()).path("data");
        assertThat(data.path("avgRating").isNull()).isTrue();
        assertThat(data.path("reviewCount").asInt()).isZero();

        JsonNode distribution = data.path("ratingDistribution");
        assertThat(distribution.isArray()).isTrue();
        assertThat(distribution).hasSize(5);
        for (int i = 0; i < 5; i++) {
            JsonNode bucket = distribution.get(i);
            assertThat(bucket.path("rating").asInt())
                    .as("bucket %s must be ordered 5 down to 1", i)
                    .isEqualTo(5 - i);
            assertThat(bucket.path("count").asLong())
                    .as("a never-reviewed user must get an all-zero-filled bucket, never an omitted one")
                    .isZero();
        }
    }

    @Test
    @DisplayName("200 with the recalculated aggregate + zero-filled distribution after a provider leaves a client "
            + "review, and the serialized body's key set is EXACTLY {avgRating, reviewCount, ratingDistribution} — "
            + "never a comment or review list")
    void should_returnAggregateAndNoCommentField_when_clientHasBeenReviewed() throws Exception {
        String clientEmail = "rating-reviewed-client-" + System.nanoTime() + "@beautica.test";
        UUID clientId = createUser(clientEmail, "CLIENT");

        String ownerEmail = "rating-reviewed-owner-" + System.nanoTime() + "@beautica.test";
        UUID ownerId = createUser(ownerEmail, "SALON_OWNER");
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at) VALUES (?, ?, ?, true, NOW(), NOW())",
                salonId, ownerId, "Salon-" + salonId);
        String masterEmail = "rating-reviewed-master-" + System.nanoTime() + "@beautica.test";
        UUID masterUserId = createUser(masterEmail, "SALON_MASTER", salonId);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'SALON_MASTER', true, NOW(), NOW())",
                masterId, masterUserId, salonId);
        UUID serviceTypeId = jdbcTemplate.queryForObject(
                "SELECT st.id FROM service_types st JOIN platform_categories pc ON pc.name = st.platform_category_name "
                        + "WHERE st.is_active = TRUE AND pc.active = TRUE AND pc.status = 'APPROVED' ORDER BY st.name_uk LIMIT 1",
                UUID.class);
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, base_duration_minutes, base_price, buffer_minutes_after, is_active, created_at, updated_at) "
                        + "VALUES (?, 'SALON', ?, 'Test Service', ?, 60, 500.00, 0, true, NOW(), NOW())",
                serviceDefId, salonId, serviceTypeId);
        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId);
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, "
                        + "booking_source, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'COMPLETED', NOW() - interval '2 hours', NOW() - interval '1 hour', "
                        + "500.00, 60, 0, 'APP', NOW(), NOW())",
                bookingId, clientId, masterId, masterServiceId, salonId);

        String reviewBody = "{\"bookingId\":\"" + bookingId + "\",\"rating\":4,\"comment\":\"Приватний коментар про клієнта\"}";
        ResponseEntity<String> reviewResp = restTemplate.exchange(
                "/api/v1/client-reviews", HttpMethod.POST,
                new HttpEntity<>(reviewBody, bearerHeaders(tokenFor(ownerEmail))), String.class);
        assertThat(reviewResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> resp = restTemplate.exchange(
                URL, HttpMethod.GET, new HttpEntity<>(bearerHeaders(tokenFor(clientEmail))), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(resp.getBody()).path("data");
        assertThat(data.path("avgRating").asDouble()).isEqualTo(4.0);
        assertThat(data.path("reviewCount").asInt()).isEqualTo(1);

        // Bucket-level assertion: the single 4-star review lands in the rating=4 bucket with
        // count=1; every other bucket (5, 3, 2, 1) is still present, zero-filled.
        JsonNode distribution = data.path("ratingDistribution");
        assertThat(distribution.isArray()).isTrue();
        assertThat(distribution).hasSize(5);
        for (JsonNode bucket : distribution) {
            long expectedCount = bucket.path("rating").asInt() == 4 ? 1L : 0L;
            assertThat(bucket.path("count").asLong())
                    .as("bucket for rating=%s", bucket.path("rating").asInt())
                    .isEqualTo(expectedCount);
        }

        // Explicit key-set assertion — the load-bearing part of this test (§ class javadoc):
        // a test that only deserializes into UserRatingResponse would silently ignore an
        // accidentally-added extra JSON field (e.g. a leaked "comment").
        Set<String> keys = new java.util.TreeSet<>();
        Iterator<String> fieldNames = data.fieldNames();
        fieldNames.forEachRemaining(keys::add);
        assertThat(keys)
                .as("the response body must expose ONLY avgRating, reviewCount, and ratingDistribution — "
                        + "never a comment or review list")
                .containsExactlyInAnyOrder("avgRating", "reviewCount", "ratingDistribution");
        assertThat(resp.getBody())
                .as("the raw response body must never contain the provider's comment text")
                .doesNotContain("Приватний коментар");
    }

    @Test
    @DisplayName("401 when the caller has no bearer token")
    void should_return401_when_unauthenticated() {
        ResponseEntity<String> resp = restTemplate.exchange(URL, HttpMethod.GET, HttpEntity.EMPTY, String.class);

        assertThat(resp.getStatusCode())
                .as("GET /users/me/rating without a token must be rejected with 401 — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("ratingDistribution exactly matches real GROUP BY counts across 4 distinct star values (5,5,3,1), "
            + "in fixed 5-down-to-1 order, with counts serialized as raw JSON numbers (not strings)")
    void should_matchRealGroupByCounts_when_clientHasMultipleDistinctRatings() throws Exception {
        String clientEmail = "rating-multi-client-" + System.nanoTime() + "@beautica.test";
        UUID clientId = createUser(clientEmail, "CLIENT");
        ProviderFixture provider = createProviderFixture();

        // Two 5-star, one 3-star, one 1-star — deliberately NOT already-sorted input order, so a
        // test that only summed things up (rather than genuinely grouping by rating) would still
        // pass; only a correct GROUP BY reproduces {5: 2, 3: 1, 1: 1} with 4 and 2 left at zero.
        insertCompletedBookingWithReview(clientId, provider, 5);
        insertCompletedBookingWithReview(clientId, provider, 3);
        insertCompletedBookingWithReview(clientId, provider, 5);
        insertCompletedBookingWithReview(clientId, provider, 1);

        ResponseEntity<String> resp = restTemplate.exchange(
                URL, HttpMethod.GET, new HttpEntity<>(bearerHeaders(tokenFor(clientEmail))), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode distribution = objectMapper.readTree(resp.getBody()).path("data").path("ratingDistribution");
        assertThat(distribution).hasSize(5);

        // Ordering is a contract, not incidental (the mobile client indexes into this list
        // positionally) — pin the exact rating value at each positional index, independent of
        // count, using a distribution where ratings are NOT all equal.
        int[] expectedRatingAtIndex = {5, 4, 3, 2, 1};
        long[] expectedCountAtIndex = {2L, 0L, 1L, 0L, 1L};
        for (int i = 0; i < 5; i++) {
            JsonNode bucket = distribution.get(i);
            assertThat(bucket.path("rating").asInt())
                    .as("position %s must hold rating=%s (5-down-to-1 ordering)", i, expectedRatingAtIndex[i])
                    .isEqualTo(expectedRatingAtIndex[i]);

            JsonNode countNode = bucket.path("count");
            assertThat(countNode.isNumber())
                    .as("count for rating=%s must serialize as a raw JSON number (wire contract for the `long "
                            + "count` field), not a quoted string — raw bucket: %s", expectedRatingAtIndex[i], bucket)
                    .isTrue();
            assertThat(countNode.asLong())
                    .as("count for rating=%s", expectedRatingAtIndex[i])
                    .isEqualTo(expectedCountAtIndex[i]);
        }
    }

    @Test
    @DisplayName("a second client's reviews never bleed into the caller's ratingDistribution (WHERE subject_client_id "
            + "isolation)")
    void should_excludeOtherClientsReviews_when_computingDistribution() throws Exception {
        String callerEmail = "rating-isolated-caller-" + System.nanoTime() + "@beautica.test";
        UUID callerId = createUser(callerEmail, "CLIENT");
        String otherEmail = "rating-isolated-other-" + System.nanoTime() + "@beautica.test";
        UUID otherClientId = createUser(otherEmail, "CLIENT");
        ProviderFixture provider = createProviderFixture();

        // Caller has a single 2-star review.
        insertCompletedBookingWithReview(callerId, provider, 2);
        // The OTHER client has three 5-star reviews from the same provider — if the WHERE clause
        // were dropped (or replaced with a global GROUP BY), these would leak into the caller's
        // rating=5 bucket.
        insertCompletedBookingWithReview(otherClientId, provider, 5);
        insertCompletedBookingWithReview(otherClientId, provider, 5);
        insertCompletedBookingWithReview(otherClientId, provider, 5);

        ResponseEntity<String> resp = restTemplate.exchange(
                URL, HttpMethod.GET, new HttpEntity<>(bearerHeaders(tokenFor(callerEmail))), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode distribution = objectMapper.readTree(resp.getBody()).path("data").path("ratingDistribution");
        for (JsonNode bucket : distribution) {
            long expectedCount = bucket.path("rating").asInt() == 2 ? 1L : 0L;
            assertThat(bucket.path("count").asLong())
                    .as("caller's rating=%s bucket must ignore the OTHER client's reviews entirely",
                            bucket.path("rating").asInt())
                    .isEqualTo(expectedCount);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /** A single reusable salon/master/service triple — several bookings/reviews can hang off it. */
    private record ProviderFixture(UUID salonId, UUID masterId, UUID masterServiceId) {}

    private ProviderFixture createProviderFixture() {
        UUID ownerId = createUser("rating-provider-owner-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at) VALUES (?, ?, ?, true, NOW(), NOW())",
                salonId, ownerId, "Salon-" + salonId);
        UUID masterUserId = createUser(
                "rating-provider-master-" + System.nanoTime() + "@beautica.test", "SALON_MASTER", salonId);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'SALON_MASTER', true, NOW(), NOW())",
                masterId, masterUserId, salonId);
        UUID serviceTypeId = jdbcTemplate.queryForObject(
                "SELECT st.id FROM service_types st JOIN platform_categories pc ON pc.name = st.platform_category_name "
                        + "WHERE st.is_active = TRUE AND pc.active = TRUE AND pc.status = 'APPROVED' ORDER BY st.name_uk LIMIT 1",
                UUID.class);
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, base_duration_minutes, base_price, buffer_minutes_after, is_active, created_at, updated_at) "
                        + "VALUES (?, 'SALON', ?, 'Test Service', ?, 60, 500.00, 0, true, NOW(), NOW())",
                serviceDefId, salonId, serviceTypeId);
        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId);
        return new ProviderFixture(salonId, masterId, masterServiceId);
    }

    /**
     * Inserts a completed booking plus its client_reviews row DIRECTLY via jdbcTemplate (not the
     * {@code POST /client-reviews} API) — the write path's own validation is covered by
     * {@code ClientReviewControllerIT}/{@code ClientReviewServiceTest}; this fixture only needs a
     * real row for {@code ClientReviewRepository#countBySubjectClientIdGroupByRating}'s GROUP BY to
     * aggregate over. V128's own migration comment sanctions this: "integration-test fixtures may
     * legitimately seed client_reviews rows directly".
     */
    private void insertCompletedBookingWithReview(UUID clientId, ProviderFixture provider, int rating) {
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, "
                        + "booking_source, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'COMPLETED', NOW() - interval '2 hours', NOW() - interval '1 hour', "
                        + "500.00, 60, 0, 'APP', NOW(), NOW())",
                bookingId, clientId, provider.masterId(), provider.masterServiceId(), provider.salonId());
        jdbcTemplate.update(
                "INSERT INTO client_reviews (id, booking_id, subject_client_id, author_master_id, salon_id, rating, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, NOW(), NOW())",
                UUID.randomUUID(), bookingId, clientId, provider.masterId(), provider.salonId(), rating);
    }

    private String tokenFor(String email) throws Exception {
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, TEST_PASSWORD), String.class);
        assertThat(resp.getStatusCode()).as("login must succeed for %s", email).isEqualTo(HttpStatus.OK);
        return objectMapper.readValue(resp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {})
                .data().accessToken();
    }

    private HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private UUID createUser(String email, String role) {
        return createUser(email, role, null);
    }

    private UUID createUser(String email, String role, UUID salonId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, salon_id, is_active, email_verified) VALUES (?, ?, ?, ?, ?, true, true)",
                id, email, passwordEncoder.encode(TEST_PASSWORD), role, salonId);
        return id;
    }
}
