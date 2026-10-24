package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.dto.BookingLinkKind;
import com.beautica.booking.service.RetiredSlugHasher;
import com.beautica.config.TestSecurityConfig;
import com.beautica.notification.sms.SmsService;
import com.beautica.support.ParityRequestId;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 392 — {@code GET /api/v1/book/s/{slug}/masters/{masterId}/availability} and {@code /bookable-days} end to
 * end (real Postgres, real security chain and per-IP buckets, anonymous caller): the salon routes answer exactly
 * what the master-slug routes answer for the same master, reach a master WITHOUT a booking slug, refuse a master
 * of another salon / an inactive master / a closed salon / a service the master does not perform with one 404,
 * keep the guest window (400), answer 410 for a retired salon slug, and spend the SAME span-weighted guest
 * availability budget.
 *
 * <p>Same context shape as {@code RetiredBookingLinkPublicIT} ({@link TestSecurityConfig} + mocked
 * {@link SmsService}) so the cached Spring context is reused. Every request carries a fresh random
 * {@code X-Forwarded-For} unless the test is about the bucket.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Salon-scoped guest availability + bookable-days (phase 392, Testcontainers)")
class PublicSalonGuestAvailabilityIT extends AbstractIntegrationTest {

    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");
    private static final String BOOK = "/api/v1/book/";
    /** Production guest window (app.booking.availability-max-days default). */
    private static final int WINDOW_DAYS = 60;
    /** Full-window bookable-days cost with one service: 1 + 61/14 = 5 tokens; 60-token bucket → 12 calls. */
    private static final int FULL_WINDOW_CALLS_PER_BUCKET = 12;

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @MockBean private SmsService smsService;

    private BookingTestFixtures fx;
    private PublicBookingPageFixtures pages;
    private LocalDate today;

    private UUID salonId;
    private String salonSlug;
    /** Slug-less SALON_MASTER (the steady state for invited salon masters: no personal link). */
    private UUID slugless;
    private UUID sluglessService;
    /** Salon master that still carries a personal slug — lets the salon route be compared to the master route. */
    private UUID slugged;
    private String sluggedSlug;
    private UUID sluggedServiceA;
    private UUID sluggedServiceB;

    @BeforeEach
    void setUp() {
        fx = new BookingTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
        pages = new PublicBookingPageFixtures(jdbcTemplate, restTemplate, objectMapper, passwordEncoder);
        today = LocalDate.now(KYIV);

        BookingTestFixtures.SalonFixture salon = fx.createSalon("gsa-owner-" + System.nanoTime() + "@beautica.test");
        salonId = salon.salonId();
        salonSlug = giveSalonSlug(salonId);

        slugless = salon.masterId();
        sluglessService = fx.createSalonService(salonId, slugless);
        fx.addWorkingHoursForEveryDay(slugless);

        slugged = fx.createExtraSalonMaster(salonId, "gsa");
        sluggedSlug = "gsa-" + slugged.toString().substring(0, 8) + "-m1";
        jdbcTemplate.update("UPDATE masters SET booking_slug = ? WHERE id = ?", sluggedSlug, slugged);
        sluggedServiceA = fx.createSalonService(salonId, slugged);
        sluggedServiceB = fx.createSalonService(salonId, slugged);
        fx.addWorkingHoursForEveryDay(slugged);
    }

    // ── parity with the master-slug routes ──────────────────────────────────────────────────────

    @Test
    @DisplayName("salon route == master-slug route for the same master: availability (1 and 2 services) and "
            + "bookable-days are byte-identical")
    void should_matchMasterSlugRoute_when_sameMasterQueried() {
        LocalDate day = today.plusDays(2);
        String one = "?date=" + day + "&serviceId=" + sluggedServiceA;
        String two = one + "&serviceId=" + sluggedServiceB;
        String days = "?from=" + today.plusDays(1) + "&to=" + today.plusDays(7) + "&serviceId=" + sluggedServiceA;

        ResponseEntity<String> salonOne = get(salonRoute(slugged, "availability") + one);
        ResponseEntity<String> salonTwo = get(salonRoute(slugged, "availability") + two);
        ResponseEntity<String> salonDays = get(salonRoute(slugged, "bookable-days") + days);

        assertThat(salonOne.getStatusCode()).as(salonOne.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(salonOne.getBody()).isEqualTo(get(BOOK + sluggedSlug + "/availability" + one).getBody())
                .isNotEqualTo("[]");
        assertThat(salonTwo.getBody()).isEqualTo(get(BOOK + sluggedSlug + "/availability" + two).getBody())
                .isNotEqualTo(salonOne.getBody());
        assertThat(salonDays.getBody()).isEqualTo(get(BOOK + sluggedSlug + "/bookable-days" + days).getBody());
    }

    @Test
    @DisplayName("a master WITHOUT a booking slug is reachable through the salon: slots + working days served")
    void should_serveSlugLessMaster_when_queriedThroughSalon() throws Exception {
        ResponseEntity<String> slots = get(salonRoute(slugless, "availability") + "?date=" + today.plusDays(1)
                + "&serviceId=" + sluglessService);
        ResponseEntity<String> days = get(salonRoute(slugless, "bookable-days") + "?from=" + today.plusDays(1)
                + "&to=" + today.plusDays(3) + "&serviceId=" + sluglessService);

        assertThat(slots.getStatusCode()).as(slots.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(slots.getBody())).isNotEmpty();
        JsonNode dayList = objectMapper.readTree(days.getBody());
        assertThat(dayList).hasSize(3);
        assertThat(dayList.get(0).path("working").asBoolean()).isTrue();
        assertThat(dayList.get(0).fieldNames()).toIterable().containsExactlyInAnyOrder("date", "working");
    }

    // ── 404 matrix (indistinguishable) ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("master of ANOTHER salon, unknown master, another master's service, inactive master → 404 on both")
    void should_return404_when_masterForeignUnknownInactiveOrServiceNotPerformed() {
        BookingTestFixtures.SalonFixture other = fx.createSalon("gsa-other-" + System.nanoTime() + "@beautica.test");
        UUID foreignService = fx.createSalonService(other.salonId(), other.masterId());
        fx.addWorkingHoursForEveryDay(other.masterId());
        UUID inactive = fx.createExtraSalonMaster(salonId, "gsa-inactive");
        UUID inactiveService = fx.createSalonService(salonId, inactive);
        fx.addWorkingHoursForEveryDay(inactive);
        jdbcTemplate.update("UPDATE masters SET is_active = false WHERE id = ?", inactive);

        List<String> foreign = assertNotFound(other.masterId(), foreignService);
        List<String> unknown = assertNotFound(UUID.randomUUID(), sluglessService);
        assertNotFound(slugless, sluggedServiceA);
        assertNotFound(slugless, UUID.randomUUID());
        List<String> inactiveBodies = assertNotFound(inactive, inactiveService);

        assertThat(foreign).as("foreign vs unknown master: byte-identical 404 bodies (X-Request-Id pinned)")
                .isEqualTo(unknown);
        assertThat(inactiveBodies).as("inactive vs unknown master: byte-identical 404 bodies").isEqualTo(unknown);
    }

    @Test
    @DisplayName("inactive salon → 404 (live slug of a closed salon is not a 410)")
    void should_return404_when_salonInactive() {
        jdbcTemplate.update("UPDATE salons SET is_active = false WHERE id = ?", salonId);

        assertNotFound(slugless, sluglessService);
    }

    // ── 410 / 400 ───────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("retired salon slug → 410 BOOKING_LINK_RETIRED on availability and bookable-days; never-issued → 404")
    void should_return410_when_salonSlugRetired() throws Exception {
        String retired = "gone-salon-" + salonId.toString().substring(0, 6);
        jdbcTemplate.update(
                "INSERT INTO retired_booking_slugs (kind, slug_hash, reason, retired_at) VALUES (?, ?, 'REVOKED', NOW())",
                BookingLinkKind.SALON.name(), RetiredSlugHasher.hash(BookingLinkKind.SALON, retired));

        ResponseEntity<String> slots = get(BOOK + "s/" + retired + "/masters/" + slugless + "/availability?date="
                + today.plusDays(1) + "&serviceId=" + sluglessService);
        ResponseEntity<String> days = get(BOOK + "s/" + retired + "/masters/" + slugless + "/bookable-days?from="
                + today.plusDays(1) + "&to=" + today.plusDays(2) + "&serviceId=" + sluglessService);
        ResponseEntity<String> unknown = get(BOOK + "s/never-issued-zz77/masters/" + slugless + "/availability?date="
                + today.plusDays(1) + "&serviceId=" + sluglessService);

        for (ResponseEntity<String> gone : java.util.List.of(slots, days)) {
            assertThat(gone.getStatusCode()).as(gone.getBody()).isEqualTo(HttpStatus.GONE);
            assertThat(objectMapper.readTree(gone.getBody()).path("data").path("code").asText())
                    .isEqualTo("BOOKING_LINK_RETIRED");
            assertThat(gone.getBody()).doesNotContain(retired);
        }
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("guest window: past date / past from / to beyond today+60 / from > to → 400; last window day 200; "
            + "malformed masterId → 400")
    void should_return400_when_outsideGuestWindowOrMalformed() {
        String svc = "&serviceId=" + sluglessService;

        assertThat(get(salonRoute(slugless, "availability") + "?date=" + today.minusDays(1) + svc).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get(salonRoute(slugless, "availability") + "?date=" + today.plusDays(WINDOW_DAYS + 1) + svc)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get(salonRoute(slugless, "bookable-days") + "?from=" + today.minusDays(1) + "&to=" + today + svc)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get(salonRoute(slugless, "bookable-days") + "?from=" + today + "&to="
                + today.plusDays(WINDOW_DAYS + 1) + svc).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get(salonRoute(slugless, "bookable-days") + "?from=" + today.plusDays(3) + "&to="
                + today.plusDays(2) + svc).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get(salonRoute(slugless, "availability") + "?date=" + today.plusDays(WINDOW_DAYS) + svc)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get(BOOK + "s/" + salonSlug + "/masters/not-a-uuid/availability?date=" + today.plusDays(1) + svc)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── throttle ────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("span-weighted: 12 full-window salon bookable-days drain the 60-token guest bucket; the 13th and "
            + "a master-route /availability from that IP are 429; another IP is served")
    void should_return429_when_salonBookableDaysDrainSharedGuestBucket() {
        String ip = PublicBookingPageFixtures.randomIp();
        String fullWindow = salonRoute(slugless, "bookable-days") + "?from=" + today + "&to="
                + today.plusDays(WINDOW_DAYS) + "&serviceId=" + sluglessService;

        for (int i = 1; i <= FULL_WINDOW_CALLS_PER_BUCKET; i++) {
            assertThat(pages.anonymousGet(fullWindow, ip).getStatusCode()).as("call %d", i).isEqualTo(HttpStatus.OK);
        }
        ResponseEntity<String> over = pages.anonymousGet(fullWindow, ip);
        ResponseEntity<String> sharedBudget = pages.anonymousGet(BOOK + sluggedSlug + "/availability?date="
                + today.plusDays(1) + "&serviceId=" + sluggedServiceA, ip);

        assertThat(over.getStatusCode().value()).isEqualTo(429);
        assertThat(over.getHeaders().getFirst("Retry-After")).isNotBlank();
        assertThat(sharedBudget.getStatusCode().value()).as("one budget for master + salon routes").isEqualTo(429);
        assertThat(get(fullWindow).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    /** Asserts 404 on both reads and returns their bodies {@code [availability, bookable-days]}. */
    private List<String> assertNotFound(UUID masterId, UUID serviceId) {
        ResponseEntity<String> slots = get(salonRoute(masterId, "availability") + "?date=" + today.plusDays(1)
                + "&serviceId=" + serviceId);
        ResponseEntity<String> days = get(salonRoute(masterId, "bookable-days") + "?from=" + today.plusDays(1)
                + "&to=" + today.plusDays(2) + "&serviceId=" + serviceId);
        assertThat(slots.getStatusCode()).as("availability %s/%s: %s", masterId, serviceId, slots.getBody())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(days.getStatusCode()).as("bookable-days %s/%s: %s", masterId, serviceId, days.getBody())
                .isEqualTo(HttpStatus.NOT_FOUND);
        return List.of(slots.getBody(), days.getBody());
    }

    private String salonRoute(UUID masterId, String leaf) {
        return BOOK + "s/" + salonSlug + "/masters/" + masterId + "/" + leaf;
    }

    private String giveSalonSlug(UUID id) {
        String slug = "gs-" + id.toString().substring(0, 8) + "-a1";
        jdbcTemplate.update("UPDATE salons SET booking_slug = ? WHERE id = ?", slug, id);
        return slug;
    }

    /** Anonymous GET from a fresh source IP with a pinned {@code X-Request-Id}, so bodies compare byte for byte. */
    private ResponseEntity<String> get(String url) {
        HttpHeaders headers = ParityRequestId.on(new HttpHeaders());
        headers.set("X-Forwarded-For", PublicBookingPageFixtures.randomIp());
        return restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }
}
