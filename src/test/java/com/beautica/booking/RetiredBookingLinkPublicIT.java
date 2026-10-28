package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.phoneotp.GuestTokenProvider;
import com.beautica.booking.PublicBookingPageFixtures.IndependentMaster;
import com.beautica.booking.PublicBookingPageFixtures.SalonPage;
import com.beautica.booking.dto.BookingLinkKind;
import com.beautica.booking.service.RetiredSlugHasher;
import com.beautica.config.BookingLinksProperties;
import com.beautica.config.TestSecurityConfig;
import com.beautica.notification.sms.SmsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.net.URI;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 379 — a retired (regenerated / revoked) booking link answers {@code 410 Gone} with
 * {@code data.code = BOOKING_LINK_RETIRED} on every public slug-keyed route, while an unknown slug stays 404,
 * a malformed one 400 and an inactive-but-not-retired master 404 (D1–D4, D6, D8). Real Postgres, real
 * security chain, real per-IP guest buckets; regenerate / revoke go through the phase 378 endpoints.
 *
 * <p>Same context shape as {@code PublicBookingBookableDaysRegressionIT} ({@link TestSecurityConfig} + mocked
 * {@link SmsService}) so the Spring context is reused. Every request carries a fresh random
 * {@code X-Forwarded-For} unless the test is about the bucket.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Retired booking link → 410 on public routes (phase 379, Testcontainers)")
class RetiredBookingLinkPublicIT extends AbstractIntegrationTest {

    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");
    private static final String BOOK = "/api/v1/book/";
    private static final String MASTER_REGENERATE = "/api/v1/booking-links/master/regenerate";
    private static final String MASTER_REVOKE = "/api/v1/booking-links/master";
    /** Production capacity of guestInfoBuckets (60 / 60 s / IP). */
    private static final int INFO_BUCKET_CAPACITY = 60;

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private GuestTokenProvider guestTokenProvider;
    @Autowired private BookingLinksProperties bookingLinksProperties;
    @MockBean private SmsService smsService;

    private PublicBookingPageFixtures pages;
    private LocalDate today;

    private boolean originalShareEnabled;

    @BeforeEach
    void setUp() {
        originalShareEnabled = bookingLinksProperties.isShareEnabled();
        pages = new PublicBookingPageFixtures(jdbcTemplate, restTemplate, objectMapper, passwordEncoder);
        today = LocalDate.now(KYIV);
        bookingLinksProperties.setShareEnabled(true);
    }

    /** {@link BookingLinksProperties} is a context singleton shared with other ITs: restore the kill-switch. */
    @AfterEach
    void restoreShareEnabled() {
        bookingLinksProperties.setShareEnabled(originalShareEnabled);
    }

    // ── MASTER: regenerate / revoke ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("master regenerate → old slug 410 (exact body, no slug echo) on info, reviews, availability, "
            + "bookable-days, HEAD info; POST booking 410 with a valid guest token and zero booking rows; new slug 200")
    void should_return410OnEveryMasterRoute_when_masterLinkRegenerated() throws Exception {
        IndependentMaster master = pages.independentMaster(true);
        UUID svc = masterServiceOf(master.serviceDefId());
        String fresh = regenerateMaster(master);
        String old = master.slug();

        ResponseEntity<String> info = get(BOOK + old + "/info");
        ResponseEntity<String> reviews = get(BOOK + old + "/reviews");
        ResponseEntity<String> availability = get(BOOK + old + "/availability?date=" + today.plusDays(1)
                + "&serviceId=" + svc);
        ResponseEntity<String> bookableDays = get(BOOK + old + "/bookable-days?from=" + today.plusDays(1)
                + "&to=" + today.plusDays(3) + "&serviceId=" + svc);
        ResponseEntity<String> head = restTemplate.exchange(BOOK + old + "/info", HttpMethod.HEAD,
                new HttpEntity<>(ipHeaders(randomIp())), String.class);
        ResponseEntity<String> booking = guestBook(old, svc, today.plusDays(1), LocalTime.of(10, 0));

        assertGone(info, old);
        assertGone(reviews, old);
        assertGone(availability, old);
        assertGone(bookableDays, old);
        assertGone(booking, old);
        assertThat(head.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(bookingCount(master.masterId())).as("no booking through a retired link (D4)").isZero();
        assertThat(get(BOOK + fresh + "/info").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("master revoke → old slug 410 on info, availability and POST booking")
    void should_return410_when_masterLinkRevoked() throws Exception {
        IndependentMaster master = pages.independentMaster(true);
        UUID svc = masterServiceOf(master.serviceDefId());
        ResponseEntity<String> revoke = call(HttpMethod.DELETE, MASTER_REVOKE, pages.tokenOf(master), master.slug());
        assertThat(revoke.getStatusCode()).as(revoke.getBody()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> info = get(BOOK + master.slug() + "/info");
        ResponseEntity<String> availability = get(BOOK + master.slug() + "/availability?date=" + today.plusDays(1)
                + "&serviceId=" + svc);
        ResponseEntity<String> booking = guestBook(master.slug(), svc, today.plusDays(1), LocalTime.of(10, 0));

        assertGone(info, master.slug());
        assertGone(availability, master.slug());
        assertGone(booking, master.slug());
        assertThat(bookingCount(master.masterId())).isZero();
    }

    @Test
    @DisplayName("info cached (200) for the old slug before regenerate → 410 right after the regenerate commits")
    void should_return410NotStaleCachedPage_when_infoWasCachedBeforeRegenerate() throws Exception {
        IndependentMaster master = pages.independentMaster(true);
        assertThat(get(BOOK + master.slug() + "/info").getStatusCode()).as("warm the cache").isEqualTo(HttpStatus.OK);

        regenerateMaster(master);

        assertGone(get(BOOK + master.slug() + "/info"), master.slug());
    }

    // ── SALON: regenerate / revoke ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("salon regenerate → /s/{old}/info and /s/{old}/reviews 410; new salon slug 200")
    void should_return410_when_salonLinkRegenerated() throws Exception {
        SalonPage salon = pages.salon("Салон 379");
        assertThat(get(BOOK + "s/" + salon.slug() + "/info").getStatusCode()).as("warm").isEqualTo(HttpStatus.OK);

        ResponseEntity<String> regenerated = call(HttpMethod.POST, salonLinks(salon.salonId()) + "/regenerate",
                salon.ownerToken(), salon.slug());
        assertThat(regenerated.getStatusCode()).as(regenerated.getBody()).isEqualTo(HttpStatus.OK);
        String fresh = objectMapper.readTree(regenerated.getBody()).path("data").path("slug").asText();

        assertGone(get(BOOK + "s/" + salon.slug() + "/info"), salon.slug());
        assertGone(get(BOOK + "s/" + salon.slug() + "/reviews"), salon.slug());
        assertThat(get(BOOK + "s/" + fresh + "/info").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("salon revoke → /s/{old}/info 410")
    void should_return410_when_salonLinkRevoked() throws Exception {
        SalonPage salon = pages.salon("Салон 379 R");

        ResponseEntity<String> revoked = call(HttpMethod.DELETE, salonLinks(salon.salonId()), salon.ownerToken(),
                salon.slug());
        assertThat(revoked.getStatusCode()).as(revoked.getBody()).isEqualTo(HttpStatus.OK);

        assertGone(get(BOOK + "s/" + salon.slug() + "/info"), salon.slug());
    }

    // ── unchanged statuses ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("unknown slug → 404 'Resource not found' (master + salon), malformed → 400, inactive "
            + "non-retired master → 404 — unchanged")
    void should_keep404And400_when_slugUnknownMalformedOrInactive() throws Exception {
        IndependentMaster inactive = pages.independentMaster(false);
        jdbcTemplate.update("UPDATE masters SET is_active = false WHERE id = ?", inactive.masterId());

        ResponseEntity<String> unknown = get(BOOK + "nobody-zz99/info");
        ResponseEntity<String> unknownSalon = get(BOOK + "s/nobody-zz99/info");
        ResponseEntity<String> malformed = get(BOOK + "Bad_Slug/info");
        ResponseEntity<String> inactiveInfo = get(BOOK + inactive.slug() + "/info");

        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(objectMapper.readTree(unknown.getBody()).path("message").asText()).isEqualTo("Resource not found");
        assertThat(objectMapper.readTree(unknown.getBody()).path("data").isNull()).isTrue();
        assertThat(unknownSalon.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(malformed.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(inactiveInfo.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("kinds are separate namespaces: a MASTER tombstone leaves /s/{same}/info 404 and a SALON "
            + "tombstone leaves /{same}/info 404")
    void should_notCrossKinds_when_tombstoneOfOtherKind() {
        insertTombstone(BookingLinkKind.MASTER, "only-master-ab12");
        insertTombstone(BookingLinkKind.SALON, "only-salon-ab12");

        assertThat(get(BOOK + "only-master-ab12/info").getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(get(BOOK + "s/only-master-ab12/info").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get(BOOK + "s/only-salon-ab12/info").getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(get(BOOK + "only-salon-ab12/info").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("equivalent spellings (HEAD, /inf%6f) of a retired info route → 410")
    void should_return410_when_equivalentSpellingOfRetiredRoute() throws Exception {
        insertTombstone(BookingLinkKind.MASTER, "gone-spell-ab12");

        ResponseEntity<String> head = restTemplate.exchange(BOOK + "gone-spell-ab12/info", HttpMethod.HEAD,
                new HttpEntity<>(ipHeaders(randomIp())), String.class);
        ResponseEntity<String> encoded = exchangeRaw(BOOK + "gone-spell-ab12/inf%6f");

        assertThat(head.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertGone(encoded, "gone-spell-ab12");
    }

    @Test
    @DisplayName("spellings the security chain itself refuses (;jsessionid=a, //api) answer exactly as for a LIVE "
            + "slug — the retired lookup never runs before the chain and never turns a refusal into a 200")
    void should_matchLiveSlugStatus_when_spellingRefusedBeforeController() {
        IndependentMaster live = pages.independentMaster(false);
        insertTombstone(BookingLinkKind.MASTER, "gone-spell-cd34");

        for (String spelling : new String[] {BOOK + "%s/info;jsessionid=a", "/" + BOOK + "%s/info"}) {
            HttpStatus liveStatus = (HttpStatus) exchangeRaw(spelling.formatted(live.slug())).getStatusCode();
            HttpStatus retiredStatus = (HttpStatus) exchangeRaw(spelling.formatted("gone-spell-cd34")).getStatusCode();

            assertThat(retiredStatus).as(spelling).isEqualTo(liveStatus).isNotEqualTo(HttpStatus.OK);
        }
    }

    @Test
    @DisplayName("retired-slug probes still spend the per-IP guestInfoBuckets budget: request 61 → 429")
    void should_return429_when_retiredSlugProbedPastInfoBucket() {
        insertTombstone(BookingLinkKind.MASTER, "gone-flood-ab12");
        String ip = randomIp();
        for (int i = 1; i <= INFO_BUCKET_CAPACITY; i++) {
            assertThat(pages.anonymousGet(BOOK + "gone-flood-ab12/info", ip).getStatusCode())
                    .as("probe %d", i).isEqualTo(HttpStatus.GONE);
        }

        ResponseEntity<String> over = pages.anonymousGet(BOOK + "gone-flood-ab12/info", ip);

        assertThat(over.getStatusCode().value()).isEqualTo(429);
    }

    @Test
    @DisplayName("anonymous HEAD: retired → 410 with the edge-cache header and no body; unknown → 404; "
            + "live → 200 — never a 401 (D3 HEAD permitAll)")
    void should_answerHeadAnonymously_when_slugRetiredUnknownOrLive() throws Exception {
        IndependentMaster master = pages.independentMaster(true);
        String old = master.slug();
        String fresh = regenerateMaster(master);

        ResponseEntity<String> retired = head(BOOK + old + "/info");
        ResponseEntity<String> retiredReviews = head(BOOK + old + "/reviews");
        ResponseEntity<String> unknown = head(BOOK + "nobody-hd99/info");
        ResponseEntity<String> live = head(BOOK + fresh + "/info");

        assertThat(retired.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(retired.getBody()).as("HEAD carries no body").isNull();
        assertThat(retired.getHeaders().getCacheControl()).isEqualTo("max-age=0, public, s-maxage=3600");
        assertThat(retiredReviews.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(live.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(live.getBody()).isNull();
    }

    @Test
    @DisplayName("two regenerates in a row → BOTH earlier slugs 410 (each tombstoned, never re-issued); latest 200")
    void should_return410ForEveryPriorSlug_when_masterRegeneratedTwice() throws Exception {
        IndependentMaster master = pages.independentMaster(true);
        String first = master.slug();
        String second = regenerateMaster(master);
        ResponseEntity<String> again = call(HttpMethod.POST, MASTER_REGENERATE, pages.tokenOf(master), second);
        assertThat(again.getStatusCode()).as(again.getBody()).isEqualTo(HttpStatus.OK);
        String third = objectMapper.readTree(again.getBody()).path("data").path("slug").asText();

        assertGone(get(BOOK + first + "/info"), first);
        assertGone(get(BOOK + second + "/info"), second);
        assertThat(third).isNotIn(first, second);
        assertThat(get(BOOK + third + "/info").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM retired_booking_slugs WHERE kind = 'MASTER' AND master_id = ?",
                Integer.class, master.masterId())).isEqualTo(2);
    }

    @Test
    @DisplayName("unknown (never-issued) slug stays 404 — not 410 — on availability, bookable-days and POST "
            + "booking (guest-service miss path, D1)")
    void should_keep404_when_unknownSlugOnGuestServiceRoutes() throws Exception {
        IndependentMaster master = pages.independentMaster(true);
        UUID svc = masterServiceOf(master.serviceDefId());
        String unknown = "nobody-gs" + ThreadLocalRandom.current().nextInt(10, 99);

        ResponseEntity<String> availability = get(BOOK + unknown + "/availability?date=" + today.plusDays(1)
                + "&serviceId=" + svc);
        ResponseEntity<String> bookableDays = get(BOOK + unknown + "/bookable-days?from=" + today.plusDays(1)
                + "&to=" + today.plusDays(3) + "&serviceId=" + svc);
        ResponseEntity<String> booking = guestBook(unknown, svc, today.plusDays(1), LocalTime.of(10, 0));

        assertThat(availability.getStatusCode()).as(availability.getBody()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(bookableDays.getStatusCode()).as(bookableDays.getBody()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(booking.getStatusCode()).as(booking.getBody()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(objectMapper.readTree(booking.getBody()).path("data").isNull())
                .as("404 body carries no BOOKING_LINK_RETIRED code").isTrue();
    }

    // ── in-flight flow + existing bookings ──────────────────────────────────────────────────────

    @Test
    @DisplayName("in-flight: availability on old slug 200 → regenerate → POST old slug 410 (no row) → the same "
            + "guest token books on the new slug 201")
    void should_rejectOldSlugButAcceptNew_when_regeneratedMidFlow() throws Exception {
        IndependentMaster master = pages.independentMaster(true);
        UUID svc = masterServiceOf(master.serviceDefId());
        String guestToken = guestTokenProvider.generate(randomPhone());
        assertThat(get(BOOK + master.slug() + "/availability?date=" + today.plusDays(1) + "&serviceId=" + svc)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        String fresh = regenerateMaster(master);

        ResponseEntity<String> old = guestBook(master.slug(), svc, today.plusDays(1), LocalTime.of(10, 0), guestToken);
        long afterOld = bookingCount(master.masterId());
        ResponseEntity<String> renewed = guestBook(fresh, svc, today.plusDays(1), LocalTime.of(10, 0), guestToken);

        assertGone(old, master.slug());
        assertThat(afterOld).isZero();
        assertThat(renewed.getStatusCode()).as(renewed.getBody()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("a booking made through the old slug keeps its cancel-token route after regenerate")
    void should_keepCancelTokenRoute_when_bookingMadeBeforeRegenerate() throws Exception {
        IndependentMaster master = pages.independentMaster(true);
        UUID svc = masterServiceOf(master.serviceDefId());
        ResponseEntity<String> created = guestBook(master.slug(), svc, today.plusDays(2), LocalTime.of(11, 0));
        assertThat(created.getStatusCode()).as(created.getBody()).isEqualTo(HttpStatus.CREATED);
        // V206: the DB holds only the token's hash — the raw token is the one handed to the guest.
        String cancelToken = objectMapper.readTree(created.getBody()).path("cancelUrl").asText()
                .replaceAll(".*/book/cancel/", "");

        regenerateMaster(master);

        assertThat(get(BOOK + "cancel/" + cancelToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM bookings WHERE master_id = ?", String.class,
                master.masterId())).isEqualTo("CONFIRMED");
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    private void assertGone(ResponseEntity<String> response, String slug) throws Exception {
        assertThat(response.getStatusCode()).as("body=%s", response.getBody()).isEqualTo(HttpStatus.GONE);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("success").asBoolean(true)).isFalse();
        assertThat(body.path("data").path("code").asText()).isEqualTo("BOOKING_LINK_RETIRED");
        assertThat(body.path("message").asText()).isEqualTo("Booking link is no longer valid");
        assertThat(response.getBody()).as("never echoes the slug").doesNotContain(slug);
        assertThat(response.getHeaders().getCacheControl())
                .as("immutable 410 is edge-cacheable (379 audit perf F1)")
                .isEqualTo("max-age=0, public, s-maxage=3600");
    }

    private String regenerateMaster(IndependentMaster master) throws Exception {
        ResponseEntity<String> response = call(HttpMethod.POST, MASTER_REGENERATE, pages.tokenOf(master), master.slug());
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody()).path("data").path("slug").asText();
    }

    private static String salonLinks(UUID salonId) {
        return "/api/v1/booking-links/salons/" + salonId;
    }

    private ResponseEntity<String> call(HttpMethod method, String url, String token, String currentSlug) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("currentSlug", currentSlug);
        return restTemplate.exchange(url, method, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> get(String url) {
        return pages.anonymousGet(url, randomIp());
    }

    private ResponseEntity<String> head(String url) {
        return restTemplate.exchange(url, HttpMethod.HEAD, new HttpEntity<>(ipHeaders(randomIp())), String.class);
    }

    /** Sent as-is (no template expansion, so {@code %6f} / {@code ;} are not re-encoded). */
    private ResponseEntity<String> exchangeRaw(String path) {
        return restTemplate.exchange(URI.create(restTemplate.getRootUri() + path), HttpMethod.GET,
                new HttpEntity<>(ipHeaders(randomIp())), String.class);
    }

    private static HttpHeaders ipHeaders(String ip) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-For", ip);
        return headers;
    }

    private void insertTombstone(BookingLinkKind kind, String slug) {
        jdbcTemplate.update(
                "INSERT INTO retired_booking_slugs (kind, slug_hash, reason, retired_at) VALUES (?, ?, 'REVOKED', NOW())",
                kind.name(), RetiredSlugHasher.hash(kind, slug));
    }

    private UUID masterServiceOf(UUID serviceDefId) {
        return jdbcTemplate.queryForObject("SELECT id FROM master_services WHERE service_def_id = ?", UUID.class,
                serviceDefId);
    }

    private long bookingCount(UUID masterId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM bookings WHERE master_id = ?", Long.class, masterId);
    }

    private ResponseEntity<String> guestBook(String slug, UUID masterServiceId, LocalDate day, LocalTime time)
            throws Exception {
        return guestBook(slug, masterServiceId, day, time, guestTokenProvider.generate(randomPhone()));
    }

    private ResponseEntity<String> guestBook(String slug, UUID masterServiceId, LocalDate day, LocalTime time,
                                             String guestToken) throws Exception {
        HttpHeaders headers = ipHeaders(randomIp());
        headers.setBearerAuth(guestToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("serviceId", masterServiceId.toString());
        body.put("startsAt", ZonedDateTime.of(day, time, KYIV).toOffsetDateTime().toString());
        body.put("name", "Олена");
        body.put("surname", "Коваль");
        body.put("privacyAccepted", true);
        body.put("privacyPolicyVersion", "2025-02-03");
        return restTemplate.exchange(BOOK + slug + "/booking", HttpMethod.POST,
                new HttpEntity<>(objectMapper.writeValueAsString(body), headers), String.class);
    }

    private static String randomPhone() {
        return "+38050" + ThreadLocalRandom.current().nextInt(1_000_000, 10_000_000);
    }

    private static String randomIp() {
        return PublicBookingPageFixtures.randomIp();
    }
}
