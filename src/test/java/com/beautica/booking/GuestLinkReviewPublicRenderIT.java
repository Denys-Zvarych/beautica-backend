package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.config.TestSecurityConfig;
import com.beautica.review.entity.Review;
import com.beautica.review.repository.ReviewRepository;
import com.beautica.support.BookableMasterSeeder;
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
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 399 D2 end-to-end: a GUEST_LINK review written through the real {@link Review#guestLinkReview} factory and
 * JPA save path (the {@code @PrePersist} label guard + the V212 CHECKs both run) renders on EVERY public review route
 * — {@code /masters/{id}/reviews}, {@code /book/{slug}/reviews}, {@code /salons/{id}/reviews},
 * {@code /book/s/{slug}/reviews} — as the «Ім'я П.» snapshot with {@code source = GUEST_LINK}, next to an APP review
 * that keeps {@code source = APP} and its account label. The guest's full surname and phone appear nowhere in the
 * anonymous, cached response bodies.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Public review routes render a GUEST_LINK review as «Ім'я П.» with source=GUEST_LINK — phase 399 IT")
class GuestLinkReviewPublicRenderIT extends AbstractIntegrationTest {

    private static final String GUEST_NAME = "Марія";
    private static final String GUEST_SURNAME = "Шевченко";
    private static final String GUEST_PHONE = "+380675554433";
    private static final String GUEST_LABEL = "Марія Ш.";
    private static final String GUEST_COMMENT = "Дуже сподобалось, дякую!";
    private static final String APP_LABEL = "Ірина К.";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private CacheManager cacheManager;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private ReviewRepository reviewRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    private PublicBookingPageFixtures pages;
    private TransactionTemplate tx;
    private String clientIp;

    @BeforeEach
    void setUp() {
        pages = new PublicBookingPageFixtures(jdbcTemplate, restTemplate, objectMapper, passwordEncoder);
        tx = new TransactionTemplate(transactionManager);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        clientIp = "10." + random.nextInt(1, 255) + "." + random.nextInt(1, 255) + "." + random.nextInt(1, 255);
        for (String cache : List.of("booking-slug-info", "reviews-by-master", "salon-booking-slug-info",
                "reviews-by-salon")) {
            cacheManager.getCache(cache).clear();
        }
    }

    @Test
    @DisplayName("GET /masters/{id}/reviews — the guest row is «Марія Ш.» + GUEST_LINK, the app row stays APP")
    void should_renderGuestLabelAndSource_when_masterReviewsListedById() throws Exception {
        PublicBookingPageFixtures.IndependentMaster master = pages.independentMaster(true);
        pages.insertReview(master, pages.insertClient(), 4, 60);
        UUID guestReviewId = persistGuestReview(master.masterId(), null);
        recalculateMaster(master.masterId());

        ResponseEntity<String> response = get("/api/v1/masters/" + master.masterId() + "/reviews");

        Map<String, JsonNode> rows = rowsById(page(response));
        assertThat(rows).hasSize(2);
        assertGuestRow(rows.get(guestReviewId.toString()));
        assertAppRowsUnchanged(rows, guestReviewId);
        assertNoGuestPii(response);
    }

    @Test
    @DisplayName("GET /book/{slug}/reviews — the anonymous master link page renders the guest snapshot + GUEST_LINK")
    void should_renderGuestLabelAndSource_when_masterSlugReviewsListed() throws Exception {
        PublicBookingPageFixtures.IndependentMaster master = pages.independentMaster(true);
        pages.insertReview(master, pages.insertClient(), 4, 60);
        UUID guestReviewId = persistGuestReview(master.masterId(), null);
        recalculateMaster(master.masterId());

        ResponseEntity<String> response = get("/api/v1/book/" + master.slug() + "/reviews");

        Map<String, JsonNode> rows = rowsById(page(response));
        assertThat(rows).hasSize(2);
        assertGuestRow(rows.get(guestReviewId.toString()));
        assertAppRowsUnchanged(rows, guestReviewId);
        assertNoGuestPii(response);
    }

    @Test
    @DisplayName("GET /salons/{id}/reviews — the salon list renders the guest snapshot + GUEST_LINK")
    void should_renderGuestLabelAndSource_when_salonReviewsListedById() throws Exception {
        SalonFixture salon = salonWithMaster();
        pages.insertSalonReview(salon.page().salonId(), salon.masterId(), pages.insertClient(), 4, 60);
        UUID guestReviewId = persistGuestReview(salon.masterId(), salon.page().salonId());
        recalculateSalon(salon.page().salonId());

        ResponseEntity<String> response = get("/api/v1/salons/" + salon.page().salonId() + "/reviews");

        Map<String, JsonNode> rows = rowsById(page(response));
        assertThat(rows).hasSize(2);
        assertGuestRow(rows.get(guestReviewId.toString()));
        assertAppRowsUnchanged(rows, guestReviewId);
        assertNoGuestPii(response);
    }

    @Test
    @DisplayName("GET /book/s/{slug}/reviews — the anonymous salon link page renders the guest snapshot + GUEST_LINK")
    void should_renderGuestLabelAndSource_when_salonSlugReviewsListed() throws Exception {
        SalonFixture salon = salonWithMaster();
        pages.insertSalonReview(salon.page().salonId(), salon.masterId(), pages.insertClient(), 4, 60);
        UUID guestReviewId = persistGuestReview(salon.masterId(), salon.page().salonId());
        recalculateSalon(salon.page().salonId());

        ResponseEntity<String> response = get("/api/v1/book/s/" + salon.page().slug() + "/reviews");

        Map<String, JsonNode> rows = rowsById(page(response));
        assertThat(rows).hasSize(2);
        assertGuestRow(rows.get(guestReviewId.toString()));
        assertAppRowsUnchanged(rows, guestReviewId);
        assertNoGuestPii(response);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private record SalonFixture(PublicBookingPageFixtures.SalonPage page, UUID masterId) {
    }

    private SalonFixture salonWithMaster() throws Exception {
        PublicBookingPageFixtures.SalonPage salon = pages.salon("Guest Review Salon " + System.nanoTime());
        UUID masterId = pages.salonMaster(salon.salonId(), "Anna", "Bila");
        BookableMasterSeeder.makeBookable(jdbcTemplate, salon.salonId(), masterId);
        return new SalonFixture(salon, masterId);
    }

    /**
     * A COMPLETED LINK-guest booking (no account) + its GUEST_LINK review, built by the production factory from the
     * booking's stored guest fields and saved through JPA, exactly as phase 401 will.
     */
    private UUID persistGuestReview(UUID masterId, UUID salonId) {
        UUID masterServiceId = jdbcTemplate.queryForObject(
                "SELECT id FROM master_services WHERE master_id = ? LIMIT 1", UUID.class, masterId);
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, "
                        + "booking_source, guest_name, guest_surname, guest_phone, created_at, updated_at) "
                        + "VALUES (?, NULL, ?, ?, ?, 'COMPLETED', NOW() - interval '3 hours', "
                        + "NOW() - interval '2 hours', 500.00, 60, 0, 'LINK', ?, ?, ?, NOW(), NOW())",
                bookingId, masterId, masterServiceId, salonId, GUEST_NAME, GUEST_SURNAME, GUEST_PHONE);
        return tx.execute(status -> {
            Booking booking = bookingRepository.findById(bookingId).orElseThrow();
            Review review = Review.guestLinkReview(booking, (short) 5, GUEST_COMMENT,
                    booking.getGuestName(), booking.getGuestSurname());
            return reviewRepository.saveAndFlush(review).getId();
        });
    }

    private void recalculateMaster(UUID masterId) {
        tx.executeWithoutResult(s -> reviewRepository.recalculateMasterRating(masterId));
    }

    private void recalculateSalon(UUID salonId) {
        tx.executeWithoutResult(s -> reviewRepository.recalculateSalonRating(salonId));
    }

    private ResponseEntity<String> get(String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-For", clientIp);
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    /** The authenticated-API routes wrap the page in {@code ApiResponse}; the {@code /book} routes return it bare. */
    private JsonNode page(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode()).as("body=%s", response.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode root = objectMapper.readTree(response.getBody());
        JsonNode items = root.path("data");
        return items.isArray() ? items : items.path("data");
    }

    private static Map<String, JsonNode> rowsById(JsonNode items) {
        Map<String, JsonNode> rows = new HashMap<>();
        items.forEach(item -> rows.put(item.path("id").asText(), item));
        return rows;
    }

    private static void assertGuestRow(JsonNode row) {
        assertThat(row).as("the guest review must be listed").isNotNull();
        assertThat(row.path("clientDisplayName").asText()).isEqualTo(GUEST_LABEL);
        assertThat(row.path("source").asText()).isEqualTo("GUEST_LINK");
        assertThat(row.path("rating").asInt()).isEqualTo(5);
        assertThat(row.path("comment").asText()).isEqualTo(GUEST_COMMENT);
        assertThat(row.path("authoredByViewer").asBoolean()).isFalse();
    }

    private static void assertAppRowsUnchanged(Map<String, JsonNode> rows, UUID guestReviewId) {
        JsonNode appRow = rows.entrySet().stream()
                .filter(e -> !e.getKey().equals(guestReviewId.toString()))
                .map(Map.Entry::getValue)
                .findFirst().orElseThrow();
        assertThat(appRow.path("source").asText()).isEqualTo("APP");
        assertThat(appRow.path("clientDisplayName").asText()).isEqualTo(APP_LABEL);
    }

    private static void assertNoGuestPii(ResponseEntity<String> response) {
        assertThat(response.getBody())
                .as("the anonymous cached body never carries the guest's full surname or phone")
                .doesNotContain(GUEST_SURNAME)
                .doesNotContain("675554433");
    }
}
