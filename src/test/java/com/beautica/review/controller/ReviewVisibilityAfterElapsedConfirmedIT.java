package com.beautica.review.controller;

import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.common.ApiResponse;
import com.beautica.review.dto.CreateReviewRequest;
import com.beautica.review.support.AbstractRatingVisibilityIT;
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
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for the literal reported bug (Phase 240).
 *
 * <p><b>What was reported:</b> a client left a review on a booking whose appointment time had
 * elapsed but whose status the provider had never moved off {@code CONFIRMED}, and then "could
 * not see the review or any rating change anywhere".
 *
 * <p><b>Why the existing coverage missed it:</b>
 * {@code ReviewIntegrationTest#should_createReview_when_confirmedBookingHasElapsed} asserts the
 * {@code 201} and stops there — it never reads the review back, and it never re-reads the
 * master profile through a cache that had already been populated. The two ratings surfaces the
 * user actually looks at were therefore untested on this path.
 *
 * <p><b>What this test adds, and why each step is load-bearing:</b>
 * <ol>
 *   <li>{@code GET /masters/{id}} is issued <b>before</b> the review. This is not a redundant
 *       probe — it PRIMES the {@code master-detail} cache with the zero-review profile. Without
 *       it the post-review read would be a cold cache miss and would pass green even with the
 *       Phase 240 eviction entirely removed, making the "no TTL wait" claim vacuous.</li>
 *   <li>{@code POST /reviews} → 201.</li>
 *   <li>{@code GET /masters/{id}/reviews} must contain the review, asserted field by field
 *       (rating, comment, serviceName) — the "I can't see my review" half of the report.</li>
 *   <li>{@code GET /masters/{id}} must report the new {@code avgRating}/{@code reviewCount}
 *       <b>immediately</b>, with no 5-minute TTL wait — the "nothing changed" half.</li>
 * </ol>
 *
 * <p>Both provider shapes are covered: an {@code INDEPENDENT_MASTER} (no salon branch) and a
 * {@code SALON_MASTER} (whose salon aggregate and salon review list must move too).
 */
@DisplayName("Review on an elapsed CONFIRMED booking — visible on every read surface immediately")
class ReviewVisibilityAfterElapsedConfirmedIT extends AbstractRatingVisibilityIT {

    private static final Logger log =
            LoggerFactory.getLogger(ReviewVisibilityAfterElapsedConfirmedIT.class);

    private static final String REVIEWS_URL = "/api/v1/reviews";
    private static final String MASTERS_URL = "/api/v1/masters";
    private static final String SALONS_URL = "/api/v1/salons";
    private static final String COMMENT = "Майстер чудовий, все сподобалось";
    private static final HttpComponentsClientHttpRequestFactory HTTP_FACTORY =
            new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault());

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @AfterAll
    static void destroyHttpFactory() throws Exception {
        HTTP_FACTORY.destroy();
    }

    @BeforeEach
    void configureHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(HTTP_FACTORY);
    }

    // ── independent master ─────────────────────────────────────────────────────

    @Test
    @DisplayName("INDEPENDENT_MASTER — the review is listed and GET /masters/{id} shows 5.00/1 with no TTL wait")
    void should_surfaceReviewAndFreshRating_when_independentMasterBookingElapsedUnclosed()
            throws Exception {
        String suffix = "-im-" + System.nanoTime();
        UUID masterUserId = seedProviderUser("im" + suffix + "@beautica.test",
                "INDEPENDENT_MASTER", null);
        UUID masterId = seedMaster(masterUserId, null, "INDEPENDENT_MASTER");
        UUID masterServiceId = seedService("INDEPENDENT_MASTER", masterUserId, masterId);
        String clientEmail = "cli" + suffix + "@beautica.test";
        String clientToken = seedClientAndLogin(clientEmail);
        UUID clientId = resolveUserIdByEmail(clientEmail);
        UUID bookingId = seedElapsedConfirmedBooking(clientId, masterId, masterServiceId, null);

        // 1. PRIME the master-detail cache with the zero-review profile.
        assertPrimedZeroReviewProfile(masterId);

        // 2. The reported action.
        log.debug("Act: client reviews an INDEPENDENT_MASTER booking that elapsed while still CONFIRMED");
        ResponseEntity<String> created = postReview(clientToken, bookingId, 5);
        assertThat(created.getStatusCode())
                .as("an elapsed-but-unclosed CONFIRMED booking must be reviewable — body: %s",
                        created.getBody())
                .isEqualTo(HttpStatus.CREATED);

        // 3. "I can't see my review" — the review must be listed on the master.
        assertReviewListed(MASTERS_URL + "/" + masterId + "/reviews", 5);

        // 4. "Nothing changed" — the aggregate must move on the very next read.
        assertMasterAggregate(masterId, "5.00", 1);
    }

    // ── salon master ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("SALON_MASTER — the review is listed on both master and salon, and both aggregates read fresh")
    void should_surfaceReviewAndFreshRating_when_salonMasterBookingElapsedUnclosed()
            throws Exception {
        String suffix = "-sm-" + System.nanoTime();
        UUID ownerId = seedProviderUser("own" + suffix + "@beautica.test", "SALON_OWNER", null);
        UUID salonId = seedSalon(ownerId);
        UUID masterUserId = seedProviderUser("sm" + suffix + "@beautica.test",
                "SALON_MASTER", salonId);
        UUID masterId = seedMaster(masterUserId, salonId, "SALON_MASTER");
        UUID masterServiceId = seedService("SALON", salonId, masterId);
        String clientEmail = "cli" + suffix + "@beautica.test";
        String clientToken = seedClientAndLogin(clientEmail);
        UUID clientId = resolveUserIdByEmail(clientEmail);
        UUID bookingId = seedElapsedConfirmedBooking(clientId, masterId, masterServiceId, salonId);

        // 1. PRIME both master-detail and salon-detail with the zero-review state.
        assertPrimedZeroReviewProfile(masterId);
        ResponseEntity<String> salonBefore = get(SALONS_URL + "/" + salonId, null);
        assertThat(salonBefore.getStatusCode())
                .as("the priming salon read must succeed — body: %s", salonBefore.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(salonBefore.getBody()).path("data").path("reviewCount").asInt())
                .as("the priming salon read must capture the zero-review state, otherwise the "
                    + "post-review salon assertion cannot distinguish fresh from stale")
                .isZero();

        // 2. The reported action.
        log.debug("Act: client reviews a SALON_MASTER booking that elapsed while still CONFIRMED");
        ResponseEntity<String> created = postReview(clientToken, bookingId, 4);
        assertThat(created.getStatusCode())
                .as("an elapsed-but-unclosed CONFIRMED salon booking must be reviewable — body: %s",
                        created.getBody())
                .isEqualTo(HttpStatus.CREATED);

        // 3. Listed on BOTH review surfaces.
        assertReviewListed(MASTERS_URL + "/" + masterId + "/reviews", 4);
        assertReviewListed(SALONS_URL + "/" + salonId + "/reviews", 4);

        // 4. Both aggregates fresh on the next read.
        assertMasterAggregate(masterId, "4.00", 1);

        ResponseEntity<String> salonAfter = get(SALONS_URL + "/" + salonId, null);
        var salonData = objectMapper.readTree(salonAfter.getBody()).path("data");
        assertRating(salonData, "4.00",
                "GET /salons/{id} must report %s with no TTL wait — a stale value here means the "
                + "salon-detail eviction did not fire");
        assertThat(salonData.path("reviewCount").asInt())
                .as("GET /salons/{id} reviewCount must be 1, actual=%s",
                        salonData.path("reviewCount").asInt())
                .isEqualTo(1);
    }

    // ── shared assertions ─────────────────────────────────────────────────────

    /**
     * Issues the pre-review {@code GET /masters/{id}} that populates the {@code master-detail}
     * cache, and pins the zero-review baseline. Both effects matter: without the read the later
     * fresh-rating assertion is a cold miss and proves nothing; without the assertions a
     * mis-seeded fixture could make the post-review numbers accidentally correct.
     */
    private void assertPrimedZeroReviewProfile(UUID masterId) throws Exception {
        ResponseEntity<String> before = get(MASTERS_URL + "/" + masterId, null);
        assertThat(before.getStatusCode())
                .as("the priming master read must succeed — body: %s", before.getBody())
                .isEqualTo(HttpStatus.OK);
        var data = objectMapper.readTree(before.getBody()).path("data");
        assertThat(data.path("reviewCount").asInt())
                .as("the priming read must capture reviewCount=0 so a surviving cached entry is "
                    + "detectable after the review lands")
                .isZero();
        assertThat(data.path("avgRating").isNull())
                .as("an unreviewed master must serve a null avgRating, never the stored 0.00 "
                    + "(Phase 240 Finding 3) — actual node: %s", data.path("avgRating"))
                .isTrue();
    }

    /**
     * Reads a review-list endpoint and asserts the created review is genuinely there, field by
     * field. Walked as a JSON tree rather than bound to a record because the master list returns
     * {@code ApiResponse<PageResponse<ReviewResponse>>} while the salon list returns
     * {@code ApiResponse<PageResponse<SalonReviewResponse>>} — two different element types over
     * the identical rating/comment/serviceName fields this regression cares about.
     */
    private void assertReviewListed(String url, int expectedRating) throws Exception {
        ResponseEntity<String> resp = get(url, null);
        assertThat(resp.getStatusCode())
                .as("%s must return 200 — body: %s", url, resp.getBody())
                .isEqualTo(HttpStatus.OK);

        var page = objectMapper.readTree(resp.getBody()).path("data");
        assertThat(page.path("totalElements").asLong())
                .as("%s must list exactly the one review just created, actual=%s — this is the "
                    + "'I can't see my review' half of the report", url,
                        page.path("totalElements").asLong())
                .isEqualTo(1L);

        var rows = page.path("data");
        assertThat(rows.size())
                .as("%s must return one row, actual=%s", url, rows.size())
                .isEqualTo(1);

        var review = rows.get(0);
        assertThat(review.path("rating").asInt())
                .as("rating must round-trip through %s, actual=%s", url,
                        review.path("rating").asInt())
                .isEqualTo(expectedRating);
        assertThat(review.path("comment").asText())
                .as("the client's comment must be readable back from %s, actual=%s", url,
                        review.path("comment").asText())
                .isEqualTo(COMMENT);
        assertThat(review.path("serviceName").asText())
                .as("the reviewed service name must be resolved on %s, actual=%s", url,
                        review.path("serviceName").asText())
                .isEqualTo(SERVICE_NAME);
    }

    private void assertMasterAggregate(UUID masterId, String expectedAvg, int expectedCount)
            throws Exception {
        ResponseEntity<String> resp = get(MASTERS_URL + "/" + masterId, null);
        assertThat(resp.getStatusCode())
                .as("GET /masters/{id} must return 200 — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.OK);

        var data = objectMapper.readTree(resp.getBody()).path("data");
        assertRating(data, expectedAvg,
                "GET /masters/{id} must report %s on the very next read — the reported bug was "
                + "this endpoint serving the pre-review average for the cache's full 5-minute TTL");
        assertThat(data.path("reviewCount").asInt())
                .as("GET /masters/{id} reviewCount must be %s, actual=%s",
                        expectedCount, data.path("reviewCount").asInt())
                .isEqualTo(expectedCount);
    }

    /**
     * Asserts a JSON {@code avgRating} node equals {@code expected}.
     *
     * <p>The explicit non-null check first is deliberate: a surviving stale cache entry serves
     * {@code "avgRating": null} (the zero-review normalisation), and parsing that text straight
     * into {@link BigDecimal} raises a bare {@code NumberFormatException} that names neither the
     * endpoint nor the expectation. The test must fail with a diagnosis, not a stack trace.
     */
    private void assertRating(com.fasterxml.jackson.databind.JsonNode data, String expected,
            String description) {
        var node = data.path("avgRating");
        assertThat(node.isNull() || node.isMissingNode())
                .as(description + " — got a null/absent avgRating instead, which is the "
                    + "zero-review payload a STALE cached profile would serve. Node: %s",
                        expected, node)
                .isFalse();
        assertThat(new BigDecimal(node.asText()))
                .as(description + ", actual=%s", expected, node.asText())
                .isEqualByComparingTo(expected);
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private ResponseEntity<String> postReview(String token, UUID bookingId, int rating)
            throws Exception {
        String body = objectMapper.writeValueAsString(
                new CreateReviewRequest(bookingId, rating, COMMENT));
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(
                REVIEWS_URL, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> get(String url, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    // ── seed helpers ──────────────────────────────────────────────────────────

    /**
     * Seeds a CLIENT and logs them in over the real {@code /auth/login} endpoint, so the token
     * driving every {@code POST /reviews} below is one the production filter chain actually
     * issued — not a hand-minted JWT that could mask a signing/claims regression.
     */
    private String seedClientAndLogin(String email) throws Exception {
        seedClientUser(email);
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, TEST_PASSWORD), String.class);
        assertThat(resp.getStatusCode())
                .as("client login must succeed — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.OK);
        return objectMapper
                .readValue(resp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {})
                .data()
                .accessToken();
    }
}
