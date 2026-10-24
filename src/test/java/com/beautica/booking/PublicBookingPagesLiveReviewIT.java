package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.beautica.support.BookableMasterSeeder;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phases 383 + 384 QA - the public review routes and the page headers through the REAL review write path
 * ({@code POST /reviews}: commit, then {@code RatingRecalculationService}, then post-commit eviction), with every
 * cache warm. The existing ITs seed {@code reviews} with SQL, fake the persisted counter the {@code /reviews}
 * "beyond the last page" shortcut reads, and publish a synthetic event - so a lag between the committed review,
 * that counter and the cache eviction was invisible to them.
 *
 * <p>Parity is asserted against the app's own endpoints ({@code /masters/by-salon/{id}},
 * {@code /salons/{id}}), never against hard-coded numbers.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Public /info + /reviews after a REAL POST /reviews - counters, pages and app parity (Phases 383/384)")
class PublicBookingPagesLiveReviewIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private CacheManager cacheManager;

    private PublicBookingPageFixtures pages;
    private String clientIp;

    @BeforeEach
    void setUp() {
        pages = new PublicBookingPageFixtures(jdbcTemplate, restTemplate, objectMapper, passwordEncoder);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        clientIp = "10." + random.nextInt(1, 255) + "." + random.nextInt(1, 255) + "." + random.nextInt(1, 255);
        for (String cache : new String[] {"booking-slug-info", "salon-booking-slug-info", "reviews-by-master",
                "reviews-by-salon"}) {
            cacheManager.getCache(cache).clear();
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    private record Client(UUID id, String token) {
    }

    private Client client() throws Exception {
        UUID id = pages.insertClient();
        String email = jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, id);
        ResponseEntity<String> login = restTemplate.postForEntity("/api/v1/auth/login",
                new LoginRequest(email, PublicBookingPageFixtures.PASSWORD), String.class);
        assertThat(login.getStatusCode()).as("login body=%s", login.getBody()).isEqualTo(HttpStatus.OK);
        String token = objectMapper.readValue(login.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {})
                .data().accessToken();
        return new Client(id, token);
    }

    /** A COMPLETED booking of {@code clientId} with {@code masterId} (salon booking when {@code salonId != null}). */
    private UUID completedBooking(UUID clientId, UUID masterId, UUID salonId) {
        UUID masterServiceId = jdbcTemplate.queryForObject(
                "SELECT id FROM master_services WHERE master_id = ? LIMIT 1", UUID.class, masterId);
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, starts_at, "
                        + "ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, "
                        + "booking_source, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'COMPLETED', "
                        + "NOW() - interval '2 hours', NOW() - interval '1 hour', 500.00, 60, 0, 'APP', NOW(), NOW())",
                bookingId, clientId, masterId, masterServiceId, salonId);
        return bookingId;
    }

    private void postReview(Client client, UUID bookingId, int rating) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(client.token());
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = restTemplate.exchange("/api/v1/reviews", HttpMethod.POST,
                new HttpEntity<>("{\"bookingId\":\"" + bookingId + "\",\"rating\":" + rating
                        + ",\"comment\":\"live-review-" + rating + "\"}", headers), String.class);
        assertThat(response.getStatusCode()).as("review body=%s", response.getBody()).isEqualTo(HttpStatus.CREATED);
    }

    private ResponseEntity<String> publicGet(String path, String ifNoneMatch) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-For", clientIp);
        if (ifNoneMatch != null) {
            headers.set(HttpHeaders.IF_NONE_MATCH, ifNoneMatch);
        }
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> authedGet(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private JsonNode okJson(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode()).as("body=%s", response.getBody()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody());
    }

    // ── master link ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("master link: a REAL review is on /reviews page 0 at once, and /info reviewCount, avgRating and "
            + "ratingDistribution agree with /reviews totalElements and the new rating")
    void should_showRealReviewEverywhereAtOnce_when_clientPostsReviewForMaster() throws Exception {
        var master = pages.independentMaster(true);
        Client client = client();
        String reviewsPath = "/api/v1/book/" + master.slug() + "/reviews";
        String infoPath = "/api/v1/book/" + master.slug() + "/info";
        // Warm EVERY cache with the "no reviews" state: info page, empty /reviews page 0.
        assertThat(okJson(publicGet(infoPath, null)).path("reviewCount").asInt()).isZero();
        ResponseEntity<String> emptyReviews = publicGet(reviewsPath, null);
        assertThat(okJson(emptyReviews).path("data")).isEmpty();

        postReview(client, completedBooking(client.id(), master.masterId(), null), 4);

        JsonNode reviews = okJson(publicGet(reviewsPath, emptyReviews.getHeaders().getETag()));
        JsonNode info = okJson(publicGet(infoPath, null));
        assertThat(reviews.path("data")).as("the new review is on page 0").hasSize(1);
        assertThat(reviews.path("data").get(0).path("rating").asInt()).isEqualTo(4);
        assertThat(reviews.path("totalElements").asInt()).isEqualTo(1);
        assertThat(info.path("reviewCount").asInt()).as("/info count == /reviews total").isEqualTo(1);
        assertThat(info.path("avgRating").decimalValue()).isEqualByComparingTo("4.0");
        assertThat(info.path("latestReviews")).hasSize(1);
        JsonNode bucket4 = info.path("ratingDistribution").get(1);
        assertThat(bucket4.path("rating").asInt()).isEqualTo(4);
        assertThat(bucket4.path("count").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("master link: a second REAL review moves page 0, the total and a stale /reviews validator to 200")
    void should_answer200ForStaleValidator_when_secondRealReviewPosted() throws Exception {
        var master = pages.independentMaster(true);
        Client client = client();
        String reviewsPath = "/api/v1/book/" + master.slug() + "/reviews?sort=NEWEST";
        postReview(client, completedBooking(client.id(), master.masterId(), null), 5);
        ResponseEntity<String> before = publicGet(reviewsPath, null);
        assertThat(okJson(before).path("totalElements").asInt()).isEqualTo(1);
        Client second = client();

        postReview(second, completedBooking(second.id(), master.masterId(), null), 2);

        ResponseEntity<String> after = publicGet(reviewsPath, before.getHeaders().getETag());
        assertThat(after.getStatusCode()).as("stale validator -> full 200").isEqualTo(HttpStatus.OK);
        assertThat(okJson(after).path("totalElements").asInt()).isEqualTo(2);
        assertThat(objectMapper.readTree(after.getBody()).path("data").get(0).path("rating").asInt())
                .as("newest first").isEqualTo(2);
    }

    // ── salon link ───────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("salon link: a REAL salon review updates /s/reviews, the header, ratingDistribution AND the master card, "
            + "each equal to the app's own endpoints")
    void should_matchAppEndpoints_when_clientPostsRealSalonReview() throws Exception {
        var salon = pages.salon("Live Review Salon " + System.nanoTime());
        UUID masterId = pages.salonMaster(salon.salonId(), "Анна", "Біла");
        BookableMasterSeeder.makeBookable(jdbcTemplate, salon.salonId(), masterId);
        Client client = client();
        String infoPath = "/api/v1/book/s/" + salon.slug() + "/info";
        String reviewsPath = "/api/v1/book/s/" + salon.slug() + "/reviews";
        assertThat(okJson(publicGet(infoPath, null)).path("masters").get(0).path("reviewCount").asInt()).isZero();
        assertThat(okJson(publicGet(reviewsPath, null)).path("data")).isEmpty();

        postReview(client, completedBooking(client.id(), masterId, salon.salonId()), 5);

        JsonNode info = okJson(publicGet(infoPath, null));
        JsonNode reviews = okJson(publicGet(reviewsPath, null));
        JsonNode appSalon = okJson(authedGet("/api/v1/salons/" + salon.salonId(), client.token())).path("data");
        JsonNode appMaster = okJson(authedGet("/api/v1/salons/" + salon.salonId() + "/masters", client.token()))
                .path("data").path("data").get(0);
        assertThat(reviews.path("data")).hasSize(1);
        assertThat(reviews.path("totalElements").asInt()).isEqualTo(1);
        assertThat(info.path("reviewCount").asInt()).isEqualTo(appSalon.path("reviewCount").asInt()).isEqualTo(1);
        assertThat(info.path("avgRating").decimalValue()).isEqualByComparingTo(appSalon.path("avgRating").decimalValue());
        JsonNode card = info.path("masters").get(0);
        assertThat(card.path("reviewCount").asInt()).as("master card count").isEqualTo(appMaster.path("reviewCount").asInt())
                .isEqualTo(1);
        assertThat(card.path("avgRating").decimalValue()).isEqualByComparingTo(appMaster.path("avgRating").decimalValue());
        assertThat(info.path("ratingDistribution").get(0).path("rating").asInt()).isEqualTo(5);
        assertThat(info.path("ratingDistribution").get(0).path("count").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("salon link: owner edits description + locationNote (PATCH /salons/{id}) -> the about tab equals GET /salons/{id} "
            + "on the next read, and a stale validator gets 200")
    void should_matchAppSalon_when_ownerEditsAboutFields() throws Exception {
        var salon = pages.salon("About Salon " + System.nanoTime());
        String infoPath = "/api/v1/book/s/" + salon.slug() + "/info";
        ResponseEntity<String> before = publicGet(infoPath, null);
        assertThat(okJson(before).path("description").isNull()).isTrue();
        HttpHeaders owner = new HttpHeaders();
        owner.setBearerAuth(salon.ownerToken());
        owner.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> patch = restTemplate.exchange("/api/v1/salons/" + salon.salonId(), HttpMethod.PATCH,
                new HttpEntity<>("{\"description\":\"Нова студія краси\",\"locationNote\":\"Вхід з двору\",\"street\":\"вул. Хрещатик\",\"buildingNo\":\"1\"}", owner),
                String.class);
        assertThat(patch.getStatusCode()).as(patch.getBody()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> after = publicGet(infoPath, before.getHeaders().getETag());
        JsonNode appSalon = okJson(authedGet("/api/v1/salons/" + salon.salonId(), salon.ownerToken())).path("data");
        assertThat(after.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode page = okJson(after);
        assertThat(page.path("description").asText()).isEqualTo(appSalon.path("description").asText())
                .isEqualTo("Нова студія краси");
        assertThat(page.path("locationNote").asText()).isEqualTo(appSalon.path("locationNote").asText())
                .isEqualTo("Вхід з двору");
    }
}
