package com.beautica.salon;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.common.ApiResponse;
import com.beautica.common.PageResponse;
import com.beautica.config.TestSecurityConfig;
import com.beautica.notification.service.NotificationOutboxService;
import com.beautica.review.dto.CreateReviewRequest;
import com.beautica.review.dto.ReviewResponse;
import com.beautica.review.dto.SalonReviewResponse;
import com.beautica.review.dto.SalonReviewSummaryResponse;
import com.beautica.salon.dto.PublicSalonResponse;
import com.beautica.service.dto.SalonServiceCatalogResponse;
import com.beautica.service.dto.SalonServiceCategoryGroup;
import com.beautica.service.dto.ServiceDefinitionResponse;
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

/**
 * Real HTTP-level, real-Postgres (Testcontainers) coverage for the three new public
 * salon-profile endpoints shipped in Phase 13.6 (Mobile "Public Salon Profile"):
 * {@code GET /salons/{id}}, {@code GET /salons/{id}/services}, {@code GET
 * /salons/{id}/reviews/summary}, {@code GET /salons/{id}/reviews?sort=...}.
 *
 * <p>Deliberately a NEW test class rather than extending {@code ReviewIntegrationTest}: this
 * feature spans three domains (salon rating, service catalog dedup/exclusion, review
 * sort/aggregation) that {@code ReviewIntegrationTest} does not touch (it only fixtures
 * INDEPENDENT_MASTER bookings, never a salon-affiliated master or salon-owned service
 * catalog). Extending it would bloat an already-green file across module boundaries and
 * risks the "never modify a passing test unless a real gap is found" constraint. All the
 * scenarios below are genuine integration gaps: the existing unit suites
 * ({@code ServiceCatalogServiceTest}, {@code ReviewServiceTest}, {@code
 * ReviewEventListenerTest}) mock the repository layer, so they never exercise the real
 * {@code DISTINCT ... EXISTS} dedup/exclusion SQL, the real four sort JPQL queries, the
 * real AFTER_COMMIT/REQUIRES_NEW rating recalculation, or the real Caffeine cache eviction
 * this class asserts against a live Postgres container.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Salon public profile (Phase 13.6) — full-flow integration")
class SalonPublicProfileIntegrationTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(SalonPublicProfileIntegrationTest.class);
    private static final String SALONS_URL   = "/api/v1/salons";
    private static final String REVIEWS_URL  = "/api/v1/reviews";
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

    // ── GET /salons/{salonId}/services ───────────────────────────────────────────

    @Test
    @DisplayName("GET /salons/{salonId}/services — dedups a shared service, excludes unassigned/inactive-master-only services, and orders the known platform category before an unknown legacy one")
    void should_groupBookableServicesByCategoryWithDedupAndExclusions_when_fetchingSalonCatalog() throws Exception {
        UUID ownerId = createSalonOwner("owner-cat-" + System.nanoTime() + "@beautica.test");
        UUID salonId = createSalon(ownerId, "Cat Salon " + System.nanoTime());

        UUID master1         = createSalonMaster(salonId, "m1-cat-" + System.nanoTime() + "@beautica.test", true);
        UUID master2         = createSalonMaster(salonId, "m2-cat-" + System.nanoTime() + "@beautica.test", true);
        UUID inactiveMaster  = createSalonMaster(salonId, "m3-cat-" + System.nanoTime() + "@beautica.test", false);

        UUID sharedService           = createSalonService(salonId, "HAIRDRESSING", "Shared Haircut");
        UUID exclusiveService        = createSalonService(salonId, "HAIRDRESSING", "Exclusive Style");
        UUID unassignedService       = createSalonService(salonId, "HAIRDRESSING", "Unassigned Perm");
        UUID legacyOkService         = createSalonService(salonId, "AAA_LEGACY_CATEGORY", "Legacy Wrap");
        UUID legacyInactiveOnlyService = createSalonService(salonId, "AAA_LEGACY_CATEGORY", "Legacy Inactive-Only");

        assignServiceToMaster(sharedService, master1);
        assignServiceToMaster(sharedService, master2);
        assignServiceToMaster(exclusiveService, master1);
        assignServiceToMaster(legacyOkService, master1);
        assignServiceToMaster(legacyInactiveOnlyService, inactiveMaster);
        // unassignedService intentionally has zero master_services rows.

        log.debug("Act: GET {}/{}/services with no Authorization header", SALONS_URL, salonId);
        ResponseEntity<String> resp =
                restTemplate.getForEntity(SALONS_URL + "/" + salonId + "/services", String.class);

        assertThat(resp.getStatusCode())
                .as("public catalog endpoint must be reachable with no auth")
                .isEqualTo(HttpStatus.OK);

        var wrapper = objectMapper.readValue(resp.getBody(),
                new TypeReference<ApiResponse<SalonServiceCatalogResponse>>() {});
        List<SalonServiceCategoryGroup> categories = wrapper.data().categories();

        assertThat(categories)
                .as("exactly the two categories with >=1 bookable service must appear")
                .hasSize(2);

        SalonServiceCategoryGroup known   = categories.get(0);
        SalonServiceCategoryGroup unknown = categories.get(1);

        assertThat(known.category())
                .as("approved platform category HAIRDRESSING must sort first, ahead of the unknown "
                        + "legacy category, despite AAA_LEGACY_CATEGORY sorting first alphabetically — "
                        + "proves ordering uses the platform-category map, not plain alphabetical order")
                .isEqualTo("HAIRDRESSING");
        assertThat(known.count())
                .as("HAIRDRESSING group must contain exactly the shared + exclusive service")
                .isEqualTo(2);
        assertThat(known.services())
                .extracting(ServiceDefinitionResponse::name)
                .as("shared service must appear exactly once despite two active assignments; "
                        + "the fully-unassigned service must be excluded")
                .containsExactlyInAnyOrder("Shared Haircut", "Exclusive Style");

        assertThat(unknown.category()).isEqualTo("AAA_LEGACY_CATEGORY");
        assertThat(unknown.count())
                .as("only the master1-assigned legacy service counts — the inactive-master-only "
                        + "one must be excluded (its only assignment is on a deactivated master)")
                .isEqualTo(1);
        assertThat(unknown.services())
                .extracting(ServiceDefinitionResponse::name)
                .containsExactly("Legacy Wrap");
    }

    @Test
    @DisplayName("GET /salons/{salonId}/services — returns an empty category list, no auth required, when the salon has zero bookable services")
    void should_returnEmptyCatalog_when_salonHasNoBookableServices() throws Exception {
        UUID ownerId = createSalonOwner("owner-empty-" + System.nanoTime() + "@beautica.test");
        UUID salonId = createSalon(ownerId, "Empty Salon " + System.nanoTime());

        ResponseEntity<String> resp =
                restTemplate.getForEntity(SALONS_URL + "/" + salonId + "/services", String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var wrapper = objectMapper.readValue(resp.getBody(),
                new TypeReference<ApiResponse<SalonServiceCatalogResponse>>() {});
        assertThat(wrapper.data().categories())
                .as("a salon with no bookable services must return an empty (not null) category list")
                .isEmpty();
    }

    // ── GET /salons/{salonId}/reviews?sort=... ───────────────────────────────────

    @Test
    @DisplayName("GET /salons/{salonId}/reviews — NEWEST/OLDEST/HIGHEST/LOWEST each return the 3 reviews in the correct order")
    void should_returnReviewsInEachSortOrder_when_multipleReviewsWithDifferentRatingsAndTimestampsExist()
            throws Exception {
        UUID ownerId       = createSalonOwner("owner-sort-" + System.nanoTime() + "@beautica.test");
        UUID salonId       = createSalon(ownerId, "Sort Salon " + System.nanoTime());
        UUID masterId      = createSalonMaster(salonId, "m-sort-" + System.nanoTime() + "@beautica.test", true);
        UUID serviceDefId  = createSalonService(salonId, "HAIRDRESSING", "Sort Service");
        assignServiceToMaster(serviceDefId, masterId);
        UUID masterServiceId = resolveMasterServiceId(masterId, serviceDefId);

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID reviewOldestHighestRated =
                createRatedSalonReview(salonId, masterId, masterServiceId, 5, now.minusHours(3));
        UUID reviewMid =
                createRatedSalonReview(salonId, masterId, masterServiceId, 3, now.minusHours(2));
        UUID reviewNewestLowestRated =
                createRatedSalonReview(salonId, masterId, masterServiceId, 1, now.minusHours(1));

        assertSalonReviewOrder(salonId, "NEWEST",
                List.of(reviewNewestLowestRated, reviewMid, reviewOldestHighestRated));
        assertSalonReviewOrder(salonId, "OLDEST",
                List.of(reviewOldestHighestRated, reviewMid, reviewNewestLowestRated));
        assertSalonReviewOrder(salonId, "HIGHEST",
                List.of(reviewOldestHighestRated, reviewMid, reviewNewestLowestRated));
        assertSalonReviewOrder(salonId, "LOWEST",
                List.of(reviewNewestLowestRated, reviewMid, reviewOldestHighestRated));
    }

    @Test
    @DisplayName("GET /salons/{salonId}/reviews/summary — rating distribution is zero-filled and the average matches the mixed-rating fixture")
    void should_zeroFillRatingDistributionAndMatchPersistedAverage_when_summaryRequested() throws Exception {
        UUID ownerId      = createSalonOwner("owner-sum-" + System.nanoTime() + "@beautica.test");
        UUID salonId      = createSalon(ownerId, "Summary Salon " + System.nanoTime());
        UUID masterId     = createSalonMaster(salonId, "m-sum-" + System.nanoTime() + "@beautica.test", true);
        UUID serviceDefId = createSalonService(salonId, "HAIRDRESSING", "Summary Service");
        assignServiceToMaster(serviceDefId, masterId);
        UUID masterServiceId = resolveMasterServiceId(masterId, serviceDefId);

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        createRatedSalonReview(salonId, masterId, masterServiceId, 5, now.minusHours(3));
        createRatedSalonReview(salonId, masterId, masterServiceId, 3, now.minusHours(2));
        createRatedSalonReview(salonId, masterId, masterServiceId, 1, now.minusHours(1));

        log.debug("Act: GET {}/{}/reviews/summary with no Authorization header", SALONS_URL, salonId);
        ResponseEntity<String> resp =
                restTemplate.getForEntity(SALONS_URL + "/" + salonId + "/reviews/summary", String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var wrapper = objectMapper.readValue(resp.getBody(),
                new TypeReference<ApiResponse<SalonReviewSummaryResponse>>() {});
        SalonReviewSummaryResponse summary = wrapper.data();

        assertThat(summary.reviewCount())
                .as("reviewCount must equal the 3 reviews submitted for this salon")
                .isEqualTo(3);
        assertThat(summary.avgRating())
                .as("avgRating must be the average of 5, 3 and 1")
                .isEqualByComparingTo("3.00");
        assertThat(summary.ratingDistribution())
                .as("distribution must always contain exactly 5 buckets (5-star down to 1-star), "
                        + "zero-filled for ratings with no reviews")
                .extracting(SalonReviewSummaryResponse.RatingBucket::rating,
                        SalonReviewSummaryResponse.RatingBucket::count)
                .containsExactly(
                        tuple(5, 1L),
                        tuple(4, 0L),
                        tuple(3, 1L),
                        tuple(2, 0L),
                        tuple(1, 1L)
                );
    }

    // ── Cross-boundary guard: INDEPENDENT_MASTER reviews must never leak into a salon ──

    @Test
    @DisplayName("GET /salons/{salonId}/reviews and .../reviews/summary — never include a review whose booking belonged to an INDEPENDENT_MASTER")
    void should_excludeIndependentMasterReview_when_computingSalonAggregatesAndList() throws Exception {
        UUID ownerId          = createSalonOwner("owner-idor-" + System.nanoTime() + "@beautica.test");
        UUID salonId          = createSalon(ownerId, "Boundary Salon " + System.nanoTime());
        UUID salonMasterId    = createSalonMaster(salonId, "m-boundary-" + System.nanoTime() + "@beautica.test", true);
        UUID salonServiceDefId = createSalonService(salonId, "HAIRDRESSING", "Boundary Service");
        assignServiceToMaster(salonServiceDefId, salonMasterId);
        UUID salonMasterServiceId = resolveMasterServiceId(salonMasterId, salonServiceDefId);

        UUID salonReviewId = createRatedSalonReview(
                salonId, salonMasterId, salonMasterServiceId, 4, OffsetDateTime.now(ZoneOffset.UTC));

        // An unrelated INDEPENDENT_MASTER review — must never leak into this salon's aggregates.
        UUID independentMasterId  = createIndependentMaster("im-boundary-" + System.nanoTime() + "@beautica.test");
        UUID independentServiceId = createIndependentMasterService(independentMasterId);
        String indClientEmail     = "cli-ind-" + System.nanoTime() + "@beautica.test";
        String indClientToken     = createClientAndGetToken(indClientEmail);
        UUID indClientId          = resolveUserIdByEmail(indClientEmail);
        UUID indBookingId         = createIndependentBooking(indClientId, independentMasterId, independentServiceId);

        log.debug("Act: POST {} rating=1 for an INDEPENDENT_MASTER booking — must never affect salon {}",
                REVIEWS_URL, salonId);
        assertThat(postReview(indClientToken, indBookingId, 1).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> summaryResp =
                restTemplate.getForEntity(SALONS_URL + "/" + salonId + "/reviews/summary", String.class);
        var summaryWrapper = objectMapper.readValue(summaryResp.getBody(),
                new TypeReference<ApiResponse<SalonReviewSummaryResponse>>() {});
        assertThat(summaryWrapper.data().reviewCount())
                .as("the independent-master review must not be counted in this salon's review_count")
                .isEqualTo(1);
        assertThat(summaryWrapper.data().avgRating())
                .as("avgRating must reflect only the salon-affiliated review (rating 4), never the "
                        + "independent-master rating 1")
                .isEqualByComparingTo("4.00");

        ResponseEntity<String> listResp =
                restTemplate.getForEntity(SALONS_URL + "/" + salonId + "/reviews", String.class);
        var listWrapper = objectMapper.readValue(listResp.getBody(),
                new TypeReference<ApiResponse<PageResponse<SalonReviewResponse>>>() {});
        assertThat(listWrapper.data().data())
                .as("salon review list must contain only the salon-affiliated review")
                .extracting(SalonReviewResponse::id)
                .containsExactly(salonReviewId);
    }

    // ── Full round trip: submit review -> salon detail + summary + list all reflect it ──

    @Test
    @DisplayName("POST /reviews for a salon booking — GET /salons/{id} and .../reviews/summary reflect the new rating, and the reviews-by-salon cache is evicted so the list reflects the new review too")
    void should_reflectNewReviewAndEvictReviewCache_when_reviewSubmittedForSalonBooking() throws Exception {
        UUID ownerId      = createSalonOwner("owner-rt-" + System.nanoTime() + "@beautica.test");
        UUID salonId      = createSalon(ownerId, "RoundTrip Salon " + System.nanoTime());
        UUID masterId     = createSalonMaster(salonId, "m-rt-" + System.nanoTime() + "@beautica.test", true);
        UUID serviceDefId = createSalonService(salonId, "HAIRDRESSING", "RoundTrip Service");
        assignServiceToMaster(serviceDefId, masterId);
        UUID masterServiceId = resolveMasterServiceId(masterId, serviceDefId);
        String clientEmail   = "cli-rt-" + System.nanoTime() + "@beautica.test";
        String clientToken   = createClientAndGetToken(clientEmail);
        UUID clientId        = resolveUserIdByEmail(clientEmail);
        UUID bookingId       = createSalonBooking(clientId, masterId, salonId, masterServiceId);

        // Prime the reviews-by-salon cache with an empty page BEFORE any review exists — this
        // page must be evicted (not served stale) once the review below commits.
        ResponseEntity<String> primeResp =
                restTemplate.getForEntity(SALONS_URL + "/" + salonId + "/reviews", String.class);
        var primeWrapper = objectMapper.readValue(primeResp.getBody(),
                new TypeReference<ApiResponse<PageResponse<SalonReviewResponse>>>() {});
        assertThat(primeWrapper.data().data())
                .as("no reviews exist yet — priming read must be empty")
                .isEmpty();

        log.debug("Act: POST {} rating=4 for salon-affiliated bookingId={}", REVIEWS_URL, bookingId);
        assertThat(postReview(clientToken, bookingId, 4).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        // GET /salons/{id} — this is the FIRST read of this salon's public profile, taken
        // after the review committed, so it must be populated with the fresh recalculated
        // rating (salon-detail caching only matters for reads that happened BEFORE the write).
        ResponseEntity<String> salonResp =
                restTemplate.getForEntity(SALONS_URL + "/" + salonId, String.class);
        var salonWrapper = objectMapper.readValue(salonResp.getBody(),
                new TypeReference<ApiResponse<PublicSalonResponse>>() {});
        assertThat(salonWrapper.data().avgRating())
                .as("GET /salons/{id} must reflect the recalculated rating after the review committed")
                .isEqualByComparingTo("4.00");
        assertThat(salonWrapper.data().reviewCount())
                .as("GET /salons/{id} reviewCount must be 1 after the first review")
                .isEqualTo(1);

        ResponseEntity<String> summaryResp =
                restTemplate.getForEntity(SALONS_URL + "/" + salonId + "/reviews/summary", String.class);
        var summaryWrapper = objectMapper.readValue(summaryResp.getBody(),
                new TypeReference<ApiResponse<SalonReviewSummaryResponse>>() {});
        assertThat(summaryWrapper.data().reviewCount()).isEqualTo(1);
        assertThat(summaryWrapper.data().avgRating()).isEqualByComparingTo("4.00");

        ResponseEntity<String> listResp =
                restTemplate.getForEntity(SALONS_URL + "/" + salonId + "/reviews", String.class);
        var listWrapper = objectMapper.readValue(listResp.getBody(),
                new TypeReference<ApiResponse<PageResponse<SalonReviewResponse>>>() {});
        assertThat(listWrapper.data().data())
                .as("stale cached empty page must have been evicted after commit — the new review "
                        + "must now appear, not the primed empty page")
                .hasSize(1);
        assertThat(listWrapper.data().data().get(0).rating())
                .as("the persisted review rating must be 4")
                .isEqualTo(4);
    }

    // ── Security smoke: permitAll wiring for all 4 endpoints, asserted explicitly ──

    @Test
    @DisplayName("Security smoke — all 4 public salon-profile endpoints return 200 with no Authorization header")
    void should_return200WithNoAuthorizationHeader_when_hittingAllFourPublicSalonEndpoints() {
        UUID ownerId = createSalonOwner("owner-smoke-" + System.nanoTime() + "@beautica.test");
        UUID salonId = createSalon(ownerId, "Smoke Salon " + System.nanoTime());

        assertThat(restTemplate.getForEntity(SALONS_URL + "/" + salonId, String.class).getStatusCode())
                .as("GET /salons/{id} must be reachable without auth")
                .isEqualTo(HttpStatus.OK);
        assertThat(restTemplate.getForEntity(SALONS_URL + "/" + salonId + "/services", String.class).getStatusCode())
                .as("GET /salons/{id}/services must be reachable without auth")
                .isEqualTo(HttpStatus.OK);
        assertThat(restTemplate.getForEntity(SALONS_URL + "/" + salonId + "/reviews/summary", String.class).getStatusCode())
                .as("GET /salons/{id}/reviews/summary must be reachable without auth")
                .isEqualTo(HttpStatus.OK);
        assertThat(restTemplate.getForEntity(SALONS_URL + "/" + salonId + "/reviews", String.class).getStatusCode())
                .as("GET /salons/{id}/reviews must be reachable without auth")
                .isEqualTo(HttpStatus.OK);
    }

    // ── fixtures — salon side ─────────────────────────────────────────────────────

    private UUID createSalonOwner(String email) {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) " +
                "VALUES (?, ?, ?, 'SALON_OWNER', true, true)",
                userId, email, passwordEncoder.encode(TEST_PASSWORD));
        return userId;
    }

    private UUID createSalon(UUID ownerId, String name) {
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, city, is_active) VALUES (?, ?, ?, 'Kyiv', true)",
                salonId, ownerId, name);
        return salonId;
    }

    /**
     * Inserts a user + matching masters row affiliated with the given salon.
     * {@code isActive=false} is used to fixture the "assigned only to an inactive master"
     * exclusion case in {@code findBookableServicesBySalon}.
     */
    private UUID createSalonMaster(UUID salonId, String email, boolean isActive) {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) " +
                "VALUES (?, ?, ?, 'SALON_MASTER', true, true)",
                userId, email, passwordEncoder.encode(TEST_PASSWORD));
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, avg_rating, review_count, is_active, created_at, updated_at) " +
                "VALUES (?, ?, ?, 'SALON_MASTER', 0.00, 0, ?, NOW(), NOW())",
                masterId, userId, salonId, isActive);
        return masterId;
    }

    private UUID createSalonService(UUID salonId, String category, String name) {
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions " +
                "(id, owner_type, owner_id, name, category, base_duration_minutes, base_price, " +
                "buffer_minutes_after, is_active, created_at, updated_at) " +
                "VALUES (?, 'SALON', ?, ?, ?, 60, 500.00, 0, true, NOW(), NOW())",
                serviceDefId, salonId, name, category);
        return serviceDefId;
    }

    private void assignServiceToMaster(UUID serviceDefId, UUID masterId) {
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) " +
                "VALUES (?, ?, ?, true, NOW(), NOW())",
                UUID.randomUUID(), masterId, serviceDefId);
    }

    private UUID resolveMasterServiceId(UUID masterId, UUID serviceDefId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM master_services WHERE master_id = ? AND service_def_id = ?",
                UUID.class, masterId, serviceDefId);
    }

    /**
     * Inserts a COMPLETED booking with {@code salon_id} set — the source-of-truth column
     * {@code ReviewService#createReview} reads via {@code booking.getSalon()} to populate
     * {@code Review.salon} (non-null only for salon-affiliated bookings).
     */
    private UUID createSalonBooking(UUID clientId, UUID masterId, UUID salonId, UUID masterServiceId) {
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings " +
                "(id, client_id, master_id, master_service_id, salon_id, status, " +
                "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, " +
                "buffer_minutes_at_booking, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, 'COMPLETED', " +
                "NOW() - interval '2 hours', NOW() - interval '1 hour', " +
                "500.00, 60, 0, NOW(), NOW())",
                bookingId, clientId, masterId, masterServiceId, salonId);
        return bookingId;
    }

    /**
     * Creates a fresh client + salon-affiliated completed booking, submits a review for it
     * via the real {@code POST /reviews} endpoint, then overrides {@code created_at} directly
     * via SQL so sort-order tests get deterministic, well-separated timestamps regardless of
     * how fast consecutive HTTP calls execute in CI.
     */
    private UUID createRatedSalonReview(
            UUID salonId, UUID masterId, UUID masterServiceId, int rating, OffsetDateTime createdAt)
            throws Exception {
        String clientEmail = "cli-rated-" + System.nanoTime() + "@beautica.test";
        String clientToken = createClientAndGetToken(clientEmail);
        UUID clientId      = resolveUserIdByEmail(clientEmail);
        UUID bookingId     = createSalonBooking(clientId, masterId, salonId, masterServiceId);

        ResponseEntity<String> createResp = postReview(clientToken, bookingId, rating);
        assertThat(createResp.getStatusCode())
                .as("fixture review creation must succeed")
                .isEqualTo(HttpStatus.CREATED);
        UUID reviewId = objectMapper.readValue(createResp.getBody(),
                new TypeReference<ApiResponse<ReviewResponse>>() {}).data().id();

        jdbcTemplate.update("UPDATE reviews SET created_at = ? WHERE id = ?", createdAt, reviewId);
        return reviewId;
    }

    private void assertSalonReviewOrder(UUID salonId, String sort, List<UUID> expectedOrder) throws Exception {
        log.debug("Act: GET {}/{}/reviews?sort={} with no Authorization header", SALONS_URL, salonId, sort);
        ResponseEntity<String> resp = restTemplate.getForEntity(
                SALONS_URL + "/" + salonId + "/reviews?sort=" + sort, String.class);
        assertThat(resp.getStatusCode())
                .as("sort=%s must return 200", sort)
                .isEqualTo(HttpStatus.OK);

        var wrapper = objectMapper.readValue(resp.getBody(),
                new TypeReference<ApiResponse<PageResponse<SalonReviewResponse>>>() {});
        assertThat(wrapper.data().data())
                .as("sort=%s must return the 3 reviews in the expected order", sort)
                .extracting(SalonReviewResponse::id)
                .containsExactlyElementsOf(expectedOrder);
    }

    // ── fixtures — independent-master side (cross-boundary guard) ────────────────

    private UUID createIndependentMaster(String email) {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) " +
                "VALUES (?, ?, ?, 'INDEPENDENT_MASTER', true, true)",
                userId, email, passwordEncoder.encode(TEST_PASSWORD));
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, master_type, avg_rating, review_count, is_active, created_at, updated_at) " +
                "VALUES (?, ?, 'INDEPENDENT_MASTER', 0.00, 0, true, NOW(), NOW())",
                masterId, userId);
        return masterId;
    }

    private UUID createIndependentMasterService(UUID masterId) {
        UUID userId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM masters WHERE id = ?", UUID.class, masterId);

        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions " +
                "(id, owner_type, owner_id, name, base_duration_minutes, base_price, " +
                "buffer_minutes_after, is_active, created_at, updated_at) " +
                "VALUES (?, 'INDEPENDENT_MASTER', ?, 'Boundary Test Service', 60, 500.00, 0, true, NOW(), NOW())",
                serviceDefId, userId);

        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) " +
                "VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId);

        return masterServiceId;
    }

    /** No salon_id — INDEPENDENT_MASTER bookings never carry a salon affiliation. */
    private UUID createIndependentBooking(UUID clientId, UUID masterId, UUID masterServiceId) {
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

    // ── fixtures — shared (client/auth) ──────────────────────────────────────────

    private String createClientAndGetToken(String email) throws Exception {
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) " +
                "VALUES (?, ?, ?, 'CLIENT', true, true)",
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
}
