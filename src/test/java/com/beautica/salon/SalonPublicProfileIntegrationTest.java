package com.beautica.salon;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.common.ApiResponse;
import com.beautica.common.PageResponse;
import com.beautica.common.RatingBucket;
import com.beautica.config.TestSecurityConfig;
import com.beautica.notification.service.NotificationOutboxService;
import com.beautica.review.dto.CreateReviewRequest;
import com.beautica.review.dto.ReviewResponse;
import com.beautica.review.dto.SalonReviewResponse;
import com.beautica.review.dto.SalonReviewSummaryResponse;
import com.beautica.salon.dto.PublicSalonResponse;
import com.beautica.salon.dto.UpdateSalonRequest;
import com.beautica.service.dto.SalonServiceCatalogResponse;
import com.beautica.service.dto.SalonServiceCategoryGroup;
import com.beautica.service.dto.ServiceDefinitionResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
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

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
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

    // Bookability-eviction guard: exercised through the REAL deactivate path so the afterCommit
    // salon-service-catalog eviction fires against the live Caffeine cache + Postgres container.
    @Autowired
    private com.beautica.master.service.MasterService masterService;

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

        // Phase 23.x free-slot gate: the two active masters need a usable schedule (with free future
        // slots) or NOTHING they perform would be catalogue-visible. inactiveMaster stays scheduleless —
        // it is is_active=false anyway, so its lone legacy service must still be excluded.
        seedUsableSchedule(master1);
        seedUsableSchedule(master2);

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
        assertThat(known.displayName())
                .as("HAIRDRESSING must resolve its real seeded PlatformCategory.displayName "
                        + "(V74__seed_taxonomy_platform_categories.sql), not the raw category slug")
                .isEqualTo("Перукарські послуги");
        assertThat(known.count())
                .as("HAIRDRESSING group must contain exactly the shared + exclusive service")
                .isEqualTo(2);
        assertThat(known.services())
                .extracting(ServiceDefinitionResponse::name)
                .as("shared service must appear exactly once despite two active assignments; "
                        + "the fully-unassigned service must be excluded")
                .containsExactlyInAnyOrder("Shared Haircut", "Exclusive Style");

        assertThat(unknown.category()).isEqualTo("AAA_LEGACY_CATEGORY");
        assertThat(unknown.displayName())
                .as("AAA_LEGACY_CATEGORY has no matching row in platform_categories, so displayName "
                        + "must fall back to the raw category slug itself, never null/blank")
                .isEqualTo("AAA_LEGACY_CATEGORY");
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

    // ── Phase 23.x free-slot gate — catalogue hides fully-booked / scheduleless / rotated ──

    @Test
    @DisplayName("GET /salons/{salonId}/services — HIDES a service whose only performing master is fully booked out")
    void should_hideService_when_onlyPerformingMasterIsFullyBooked() throws Exception {
        UUID ownerId = createSalonOwner("owner-fb-" + System.nanoTime() + "@beautica.test");
        UUID salonId = createSalon(ownerId, "FullyBooked Salon " + System.nanoTime());
        UUID master = createSalonMaster(salonId, "m-fb-" + System.nanoTime() + "@beautica.test", true);
        UUID service = createSalonService(salonId, "MANICURE", "Booked Manicure");
        assignServiceToMaster(service, master);
        // Usable schedule but every slot occupied → no free future slot → not bookable.
        seedFullyBookedMasterFor(master, service);

        assertThat(catalogueServiceIds(salonId))
                .as("a service whose only master has no free future slot must be hidden from the catalogue")
                .isEmpty();
    }

    @Test
    @DisplayName("GET /salons/{salonId}/services — HIDES a service whose only performing master has no schedule")
    void should_hideService_when_onlyPerformingMasterIsScheduleless() throws Exception {
        UUID ownerId = createSalonOwner("owner-sl-" + System.nanoTime() + "@beautica.test");
        UUID salonId = createSalon(ownerId, "Scheduleless Salon " + System.nanoTime());
        UUID master = createSalonMaster(salonId, "m-sl-" + System.nanoTime() + "@beautica.test", true);
        UUID service = createSalonService(salonId, "MANICURE", "Scheduleless Manicure");
        assignServiceToMaster(service, master);
        // No schedule seeded at all → no working day in the horizon → no bookable slot.

        assertThat(catalogueServiceIds(salonId))
                .as("a service whose only master has no usable schedule must be hidden")
                .isEmpty();
    }

    @Test
    @DisplayName("GET /salons/{salonId}/services — SHOWS a service when at least one performing master has a free slot (fully-booked sibling doesn't hide it)")
    void should_showService_when_atLeastOneMasterHasFreeSlot() throws Exception {
        UUID ownerId = createSalonOwner("owner-mix-" + System.nanoTime() + "@beautica.test");
        UUID salonId = createSalon(ownerId, "Mixed Salon " + System.nanoTime());
        UUID service = createSalonService(salonId, "MANICURE", "Shared Manicure");

        UUID bookedMaster = createSalonMaster(salonId, "m-booked-" + System.nanoTime() + "@beautica.test", true);
        assignServiceToMaster(service, bookedMaster);
        seedFullyBookedMasterFor(bookedMaster, service);

        UUID freeMaster = createSalonMaster(salonId, "m-free-" + System.nanoTime() + "@beautica.test", true);
        assignServiceToMaster(service, freeMaster);
        seedUsableSchedule(freeMaster);

        assertThat(catalogueServiceIds(salonId))
                .as("one master with a free slot keeps the shared service visible despite a fully-booked sibling")
                .containsExactly(service);
    }

    @Test
    @DisplayName("GET /salons/{salonId}/services — a rotated-away master's assignment must NOT leak the service into its OLD salon's catalogue")
    void should_notLeakRotatedMasterService_intoOldSalonCatalogue() throws Exception {
        UUID ownerA = createSalonOwner("owner-a-" + System.nanoTime() + "@beautica.test");
        UUID salonA = createSalon(ownerA, "Salon A " + System.nanoTime());
        UUID ownerB = createSalonOwner("owner-b-" + System.nanoTime() + "@beautica.test");
        UUID salonB = createSalon(ownerB, "Salon B " + System.nanoTime());

        UUID serviceOfA = createSalonService(salonA, "MANICURE", "A's Manicure");
        // The only performing master now belongs to salon B but kept an active assignment to A's service,
        // and has a perfectly bookable schedule. A's catalogue must still not surface the service.
        UUID rotated = createSalonMaster(salonB, "m-rot-" + System.nanoTime() + "@beautica.test", true);
        assignServiceToMaster(serviceOfA, rotated);
        seedUsableSchedule(rotated);

        assertThat(catalogueServiceIds(salonA))
                .as("a rotated master's stale assignment must not keep salon A's service visible")
                .isEmpty();
    }

    @Test
    @DisplayName("INVARIANT — catalogue-visible service ⇒ non-empty bookable master-list (the two gates can never diverge)")
    void should_holdInvariant_catalogueVisibleImpliesNonEmptyBookableMasterList() throws Exception {
        UUID ownerId = createSalonOwner("owner-inv-" + System.nanoTime() + "@beautica.test");
        UUID salonId = createSalon(ownerId, "Invariant Salon " + System.nanoTime());

        // serviceX: performed by a bookable master (free slots) → must be catalogue-visible.
        UUID serviceX = createSalonService(salonId, "MANICURE", "Bookable X");
        UUID masterX = createSalonMaster(salonId, "m-x-" + System.nanoTime() + "@beautica.test", true);
        assignServiceToMaster(serviceX, masterX);
        seedUsableSchedule(masterX);

        // serviceY: performed only by a fully-booked master → must be catalogue-hidden.
        UUID serviceY = createSalonService(salonId, "HAIRDRESSING", "Booked Y");
        UUID masterY = createSalonMaster(salonId, "m-y-" + System.nanoTime() + "@beautica.test", true);
        assignServiceToMaster(serviceY, masterY);
        seedFullyBookedMasterFor(masterY, serviceY);

        java.util.Set<UUID> visible = catalogueServiceIds(salonId);
        assertThat(visible)
                .as("only the service with a bookable master is catalogue-visible")
                .containsExactly(serviceX);

        // The anti-drift lock: EVERY catalogue-visible service must resolve to a non-empty bookable
        // master-list from the OTHER gate (BookingMasterService). If the two gates ever diverge, this
        // fails before a client can pick a service whose master picker is empty.
        for (UUID sid : visible) {
            assertThat(bookableMasterIds(salonId, sid))
                    .as("catalogue-visible service %s must have >=1 bookable master", sid)
                    .isNotEmpty();
        }

        // Symmetric direction: the hidden service's master-list is empty — both gates agree it's not bookable.
        assertThat(bookableMasterIds(salonId, serviceY))
                .as("the catalogue-hidden service must also have an empty bookable master-list")
                .isEmpty();
    }

    @Test
    @DisplayName("GET /salons/{salonId}/services — deactivating the SOLE performing master EVICTS the catalogue so the service disappears on the very next call (no 60s TTL wait)")
    void should_evictCatalogueImmediately_when_solePerformingMasterDeactivated() throws Exception {
        UUID ownerId = createSalonOwner("owner-evict-" + System.nanoTime() + "@beautica.test");
        UUID salonId = createSalon(ownerId, "Evict Salon " + System.nanoTime());
        UUID master = createSalonMaster(salonId, "m-evict-" + System.nanoTime() + "@beautica.test", true);
        UUID service = createSalonService(salonId, "MANICURE", "Sole Manicure");
        assignServiceToMaster(service, master);
        seedUsableSchedule(master);

        // Prime the salon-service-catalog cache: the service is visible while its sole performer is active.
        assertThat(catalogueServiceIds(salonId))
                .as("sole active performer with a free future slot must make the SALON service catalogue-visible")
                .containsExactly(service);

        // Deactivate the sole performer through the REAL service path so the afterCommit eviction fires.
        log.debug("Act: deactivate sole performing master {} of salon {} via MasterService.deactivateMaster",
                master, salonId);
        masterService.deactivateMaster(ownerId, master);

        // The very next call must reflect the deactivation immediately. Without the afterCommit eviction
        // of salon-service-catalog, the primed cache entry would keep serving the service for up to the
        // 60s TTL — this assertion is RED on the un-fixed code and GREEN once the eviction is wired.
        assertThat(catalogueServiceIds(salonId))
                .as("deactivating the sole performing master must evict the catalogue so its SALON service "
                        + "vanishes on the next call, not after the 60s TTL")
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

    // ── Phase 10.6 bugfix regression: taxonomy locality must reach the public DTO ──

    @Test
    @DisplayName("GET /salons/{salonId} — after the owner PATCHes taxonomy locality (cityId/street), "
            + "the public unauthenticated response carries it (was previously silently dropped)")
    void should_exposeTaxonomyLocality_when_ownerUpdatesSalonAndPublicProfileIsFetched() throws Exception {
        String ownerEmail = "owner-locality-" + System.nanoTime() + "@beautica.test";
        UUID ownerId = createSalonOwner(ownerEmail);
        UUID salonId = createSalon(ownerId, "Locality Salon " + System.nanoTime());
        String ownerToken = loginAndGetToken(ownerEmail);

        UUID cityId = jdbcTemplate.queryForObject(
                "SELECT id FROM cities WHERE name_uk = 'Вінниця' LIMIT 1", UUID.class);

        // UpdateSalonRequest field order: name, description, city, region, address,
        // cityId, districtId, street, buildingNo, locationNote, phone, instagramUrl.
        var updateRequest = new UpdateSalonRequest(
                null, null, null, null, null,
                cityId, null, "Khreshchatyk St", "22", "Near the fountain",
                null, null);

        log.debug("Act: PATCH {}/{} with taxonomy locality fields as the owner", SALONS_URL, salonId);
        ResponseEntity<String> patchResp = restTemplate.exchange(
                SALONS_URL + "/" + salonId, HttpMethod.PATCH,
                new HttpEntity<>(objectMapper.writeValueAsString(updateRequest), bearerHeaders(ownerToken)),
                String.class);
        assertThat(patchResp.getStatusCode())
                .as("owner PATCH of taxonomy locality fields must succeed")
                .isEqualTo(HttpStatus.OK);

        log.debug("Act: GET {}/{} with no Authorization header", SALONS_URL, salonId);
        ResponseEntity<String> publicResp =
                restTemplate.getForEntity(SALONS_URL + "/" + salonId, String.class);
        assertThat(publicResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        var wrapper = objectMapper.readValue(publicResp.getBody(),
                new TypeReference<ApiResponse<PublicSalonResponse>>() {});
        PublicSalonResponse publicSalon = wrapper.data();

        assertThat(publicSalon.cityId())
                .as("public salon profile must carry the taxonomy cityId the owner just wrote — "
                        + "this was previously always null because PublicSalonResponse only mapped "
                        + "the legacy free-text city/region/address fields, which SalonService stopped "
                        + "writing back in Phase 10.6")
                .isEqualTo(cityId);
        assertThat(publicSalon.street())
                .as("public salon profile must carry the structured street the owner just wrote")
                .isEqualTo("Khreshchatyk St");
        assertThat(publicSalon.buildingNo()).isEqualTo("22");
        assertThat(publicSalon.locationNote()).isEqualTo("Near the fountain");
        assertThat(publicSalon.city())
                .as("backward-compat: the legacy free-text city ('Kyiv', set at fixture creation) "
                        + "must survive a taxonomy-only PATCH untouched — updateSalon() never calls "
                        + "setCity/setRegion/setAddress (Phase 10.6), so the new taxonomy fields are "
                        + "additive, not a replacement, for pre-existing legacy data")
                .isEqualTo("Kyiv");
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
                "(id, owner_type, owner_id, name, category, service_type_id, base_duration_minutes, base_price, " +
                "buffer_minutes_after, is_active, created_at, updated_at) " +
                "VALUES (?, 'SALON', ?, ?, ?, ?, 60, 500.00, 0, true, NOW(), NOW())",
                serviceDefId, salonId, name, category, resolveServiceTypeId(category));
        return serviceDefId;
    }

    /**
     * Per-test-instance set of every {@code service_type_id} already handed out by
     * {@link #resolveServiceTypeId(String)}, so a later call — even for a different
     * {@code category} — never repeats one. A plain instance field, not
     * {@code static}: JUnit 5 builds a fresh test instance per {@code @Test} method,
     * so this resets naturally and never leaks allocations across tests.
     */
    private final java.util.Set<UUID> usedServiceTypeIds = new java.util.HashSet<>();

    /**
     * Allocates the NEXT unused, real Flyway-seeded {@code service_types.id} for the
     * FK that V111 made mandatory on {@code service_definitions} (NOT NULL, ON DELETE
     * RESTRICT).
     *
     * <p>Prefers a type whose platform category equals {@code category}, keeping the row
     * category-coherent for fixtures whose {@code category} the catalog-grouping assertions
     * key off. Falls back to any active seeded type for legacy/unknown categories (e.g.
     * {@code AAA_LEGACY_CATEGORY}) that have no matching platform category. The
     * {@code service_type_id} itself is never asserted on here — grouping keys off the
     * {@code category} column — so the fallback preserves each test's original intent.
     *
     * <p><b>Why "next unused" and not "the first match":</b> V121 added
     * {@code ux_service_def_owner_service_type_active} — a partial UNIQUE index on
     * {@code service_definitions(owner_type, owner_id, service_type_id) WHERE
     * is_active = true} enforcing "reject duplicate active service per owner and
     * type" (commit 274f15e). This method used to always return the SAME (category,
     * fallback) type, so a test seeding several active services in one category — or
     * several fallback-category services — for the SAME salon collided on that
     * shared type and threw {@code DuplicateKeyException}. Skipping already-used ids
     * keeps every such fixture's owner-scoped type set collision-free while staying
     * category-coherent. Ordering ({@code ORDER BY st.name_uk} / {@code slug}) is
     * deterministic — no random pick — so allocation, and therefore test runs, stay
     * reproducible.
     */
    private UUID resolveServiceTypeId(String category) {
        List<UUID> candidates = jdbcTemplate.queryForList(
                "SELECT st.id FROM service_types st " +
                "JOIN platform_categories pc ON pc.name = st.platform_category_name " +
                "WHERE st.platform_category_name = ? AND st.is_active = TRUE " +
                "AND pc.active = TRUE AND pc.status = 'APPROVED' " +
                "ORDER BY st.name_uk",
                UUID.class, category);
        if (candidates.isEmpty()) {
            candidates = jdbcTemplate.queryForList(
                    "SELECT id FROM service_types WHERE is_active = TRUE ORDER BY slug", UUID.class);
        }
        for (UUID candidate : candidates) {
            if (usedServiceTypeIds.add(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "resolveServiceTypeId(" + category + ") pool exhausted (" + candidates.size()
                        + " candidates) — this test seeds more distinct services under this category "
                        + "than the seeded service_types supply can cover without repeating a type "
                        + "(V121 forbids two active service_definitions of the same type for one owner)");
    }

    /** Any active seeded {@code service_types.id} — used where the type is FK-only. */
    private UUID anyServiceTypeId() {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM service_types WHERE is_active = TRUE ORDER BY slug LIMIT 1",
                UUID.class);
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

    // ── Phase 23.x free-slot gate fixtures (schedule + bookings) ─────────────────

    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");

    /** "Today" in Kyiv — the same civil day the free-slot gate computes from the autowired clock. */
    private LocalDate kyivToday() {
        return LocalDate.now(KYIV);
    }

    private UUID insertWeeklySchedule(UUID masterId, LocalDate validFrom, LocalDate validTo) {
        UUID scheduleId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO weekly_schedules (id, master_id, valid_from, valid_to, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, NOW(), NOW())",
                scheduleId, masterId, validFrom, validTo);
        return scheduleId;
    }

    private void insertInterval(UUID scheduleId, int isoDow, LocalTime start, LocalTime end) {
        jdbcTemplate.update(
                "INSERT INTO working_intervals (id, schedule_id, day_of_week, start_time, end_time) "
                        + "VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), scheduleId, isoDow, start, end);
    }

    /**
     * Open-ended weekly template with a 09:00–17:00 interval on today's ISO weekday — many free future
     * slots, so the master passes the free-slot gate (used to make a catalogue service bookable).
     */
    private void seedUsableSchedule(UUID masterId) {
        UUID scheduleId = insertWeeklySchedule(masterId, kyivToday(), null);
        insertInterval(scheduleId, kyivToday().getDayOfWeek().getValue(),
                LocalTime.of(9, 0), LocalTime.of(17, 0));
    }

    /**
     * Makes {@code masterId} FULLY BOOKED for {@code serviceDefId}: a schedule whose only working day is
     * today+7 with a single 60-min slot [10:00,11:00] (the seeded services are 60 min → exactly one
     * slot), then a CONFIRMED guest booking occupying it. No free future slot remains anywhere in the
     * horizon, so the free-slot gate must hide the service unless another master can perform it.
     */
    private void seedFullyBookedMasterFor(UUID masterId, UUID serviceDefId) {
        LocalDate day = kyivToday().plusDays(7);
        UUID scheduleId = insertWeeklySchedule(masterId, day, day);
        insertInterval(scheduleId, day.getDayOfWeek().getValue(), LocalTime.of(10, 0), LocalTime.of(11, 0));

        UUID masterServiceId = resolveMasterServiceId(masterId, serviceDefId);
        OffsetDateTime startsAt = day.atTime(10, 0).atZone(KYIV).toOffsetDateTime();
        OffsetDateTime endsAt = day.atTime(11, 0).atZone(KYIV).toOffsetDateTime();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, master_id, master_service_id, status, booking_source, "
                        + "guest_name, guest_phone, cancel_token, starts_at, ends_at, price_at_booking, "
                        + "duration_minutes_at_booking, buffer_minutes_at_booking, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'CONFIRMED', 'LINK', 'Guest', '+380501112233', ?, ?, ?, "
                        + "500.00, 60, 0, NOW(), NOW())",
                UUID.randomUUID(), masterId, masterServiceId, UUID.randomUUID(), startsAt, endsAt);
    }

    /** GET the salon catalogue and return the flat set of visible service-definition ids. */
    private java.util.Set<UUID> catalogueServiceIds(UUID salonId) throws Exception {
        ResponseEntity<String> resp =
                restTemplate.getForEntity(SALONS_URL + "/" + salonId + "/services", String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var wrapper = objectMapper.readValue(resp.getBody(),
                new TypeReference<ApiResponse<SalonServiceCatalogResponse>>() {});
        java.util.Set<UUID> ids = new java.util.HashSet<>();
        wrapper.data().categories().forEach(g -> g.services().forEach(s -> ids.add(s.id())));
        return ids;
    }

    /** GET the bookable master-list for a service and return the master ids (the second gate). */
    private List<UUID> bookableMasterIds(UUID salonId, UUID serviceDefId) throws Exception {
        ResponseEntity<String> resp = restTemplate.getForEntity(
                SALONS_URL + "/" + salonId + "/services/" + serviceDefId + "/masters", String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(resp.getBody()).get("data");
        List<UUID> ids = new java.util.ArrayList<>();
        data.forEach(n -> ids.add(UUID.fromString(n.get("masterId").asText())));
        return ids;
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
                "(id, owner_type, owner_id, name, service_type_id, base_duration_minutes, base_price, " +
                "buffer_minutes_after, is_active, created_at, updated_at) " +
                "VALUES (?, 'INDEPENDENT_MASTER', ?, 'Boundary Test Service', ?, 60, 500.00, 0, true, NOW(), NOW())",
                serviceDefId, userId, anyServiceTypeId());

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
