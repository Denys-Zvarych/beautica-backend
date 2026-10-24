package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.phoneotp.GuestTokenProvider;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 393 — {@code POST /api/v1/book/s/{slug}/masters/{masterId}/booking} end to end (real Postgres, real
 * security chain): one booking per master, two masters booked one after another with ONE guest token (one OTP),
 * each with its own visit, cancel link and LINK rows; the guest phone reaches the salon owner but never the
 * salon master (phase 386); a 409 on the second master leaves the first booking intact (each call its own
 * transaction); foreign / unknown master, another master's service and a retired salon slug are refused before
 * any row is written; token / consent / comment rules are the master route's.
 *
 * <p>Same context shape as {@code RetiredBookingLinkPublicIT} ({@link TestSecurityConfig} + mocked
 * {@link SmsService}). Every POST carries a fresh random {@code X-Forwarded-For}, so the 10 / 15 min guest-booking
 * bucket never leaks between tests, and a pinned {@code X-Request-Id} ({@link ParityRequestId}) so refusal bodies
 * can be compared byte for byte. Wire-level SMS behaviour is in {@code SalonGuestBookingSmsIT}.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Salon-scoped guest booking — one per master, one guest token (phase 393, Testcontainers)")
class PublicSalonGuestBookingIT extends AbstractIntegrationTest {

    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");
    private static final String BOOK = "/api/v1/book/";
    private static final String POLICY = "2026-10-20";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private GuestTokenProvider guestTokenProvider;
    @MockBean private SmsService smsService;

    private BookingTestFixtures fx;
    private BookingTestFixtures.SalonFixture salon;
    private String salonSlug;
    private UUID masterA;
    private UUID serviceA;
    private UUID masterB;
    private UUID serviceB;
    private LocalDate day;
    private String guestPhone;

    @BeforeEach
    void setUp() {
        fx = new BookingTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
        salon = fx.createSalon("gsb-owner-" + System.nanoTime() + "@beautica.test");
        salonSlug = "gb-" + salon.salonId().toString().substring(0, 8) + "-b1";
        jdbcTemplate.update("UPDATE salons SET booking_slug = ? WHERE id = ?", salonSlug, salon.salonId());
        masterA = salon.masterId();
        serviceA = fx.createSalonService(salon.salonId(), masterA);
        fx.addWorkingHoursForEveryDay(masterA);
        masterB = fx.createExtraSalonMaster(salon.salonId(), "gsb");
        serviceB = fx.createSalonService(salon.salonId(), masterB);
        fx.addWorkingHoursForEveryDay(masterB);
        day = LocalDate.now(KYIV).plusDays(2);
        guestPhone = "+38067" + ThreadLocalRandom.current().nextInt(1_000_000, 10_000_000);
    }

    // ── happy path: one call per master, one guest token ────────────────────────────────────────

    @Test
    @DisplayName("two masters booked sequentially with ONE guest token → 2×201, one visit + cancel link each, "
            + "LINK rows in the salon with the guest's phone, consent and per-master comment")
    void should_createOneVisitPerMaster_when_twoMastersBookedWithOneGuestToken() throws Exception {
        String token = guestTokenProvider.generate(guestPhone);

        ResponseEntity<String> first = book(masterA, List.of(serviceA), LocalTime.of(10, 0), token, "Коментар А");
        ResponseEntity<String> second = book(masterB, List.of(serviceB), LocalTime.of(11, 0), token, "Коментар Б");

        JsonNode a = created(first);
        JsonNode b = created(second);
        assertThat(a.path("appointmentId").asText()).isNotBlank().isNotEqualTo(b.path("appointmentId").asText());
        assertThat(a.path("cancelUrl").asText()).contains("/book/cancel/").isNotEqualTo(b.path("cancelUrl").asText());
        assertThat(a.path("durationMinutes").asInt()).isEqualTo(60);
        for (UUID master : List.of(masterA, masterB)) {
            Map<String, Object> row = jdbcTemplate.queryForMap("SELECT b.status, b.booking_source, b.salon_id, "
                    + "b.guest_phone, b.client_id, b.guest_privacy_policy_version, a.client_comment "
                    + "FROM bookings b JOIN appointments a ON a.id = b.appointment_id WHERE b.master_id = ?", master);
            assertThat(row.get("status")).isEqualTo("CONFIRMED");
            assertThat(row.get("booking_source")).isEqualTo("LINK");
            assertThat(row.get("salon_id")).isEqualTo(salon.salonId());
            assertThat(row.get("guest_phone")).isEqualTo(guestPhone);
            assertThat(row.get("client_id")).isNull();
            assertThat(row.get("guest_privacy_policy_version")).isEqualTo(POLICY);
            assertThat(row.get("client_comment")).isEqualTo(master.equals(masterA) ? "Коментар А" : "Коментар Б");
        }
        for (JsonNode created : List.of(a, b)) {
            String cancelToken = created.path("cancelUrl").asText().replaceAll(".*/book/cancel/", "");
            assertThat(get(BOOK + "cancel/" + cancelToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    @DisplayName("legacy single serviceId body works on the salon route too (master-route parity)")
    void should_createSingleBooking_when_legacyServiceIdBody() throws Exception {
        Map<String, Object> body = body(null, LocalTime.of(12, 0), null);
        body.put("serviceId", serviceA.toString());

        JsonNode created = created(post(masterA, body, guestTokenProvider.generate(guestPhone)));

        assertThat(created.path("bookingId").asText()).isNotBlank();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM bookings WHERE master_id = ? AND "
                + "appointment_id IS NULL AND booking_source = 'LINK'", Integer.class, masterA)).isEqualTo(1);
    }

    // ── phone visibility (phase 386) ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("phase 386: the salon owner sees the guest phone on the salon-route booking; the performing "
            + "salon master gets null and the digits are nowhere in the body")
    void should_showPhoneToOwnerOnly_when_bookedThroughSalonRoute() throws Exception {
        UUID bookingId = UUID.fromString(created(book(masterA, List.of(serviceA), LocalTime.of(10, 0),
                guestTokenProvider.generate(guestPhone), null)).path("bookingId").asText());

        ResponseEntity<String> owner = detail(bookingId, fx.tokenFor(salon.ownerEmail()));
        ResponseEntity<String> master = detail(bookingId, fx.tokenFor(salon.masterEmail()));

        assertThat(objectMapper.readTree(owner.getBody()).path("data").path("guestPhone").asText()).isEqualTo(guestPhone);
        assertThat(master.getStatusCode()).as(master.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(master.getBody()).path("data").path("guestPhone").isNull()).isTrue();
        assertThat(master.getBody()).doesNotContain(guestPhone.substring(4));
    }

    // ── refusals: nothing written ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("master of another salon, unknown master, another master's service → 404, zero rows")
    void should_return404AndWriteNothing_when_masterForeignUnknownOrServiceNotPerformed() throws Exception {
        BookingTestFixtures.SalonFixture other = fx.createSalon("gsb-other-" + System.nanoTime() + "@beautica.test");
        UUID foreignService = fx.createSalonService(other.salonId(), other.masterId());
        fx.addWorkingHoursForEveryDay(other.masterId());
        String token = guestTokenProvider.generate(guestPhone);

        ResponseEntity<String> foreign = book(other.masterId(), List.of(foreignService), LocalTime.of(10, 0), token, null);
        ResponseEntity<String> unknown = book(UUID.randomUUID(), List.of(serviceA), LocalTime.of(10, 0), token, null);
        ResponseEntity<String> wrongService = book(masterA, List.of(serviceB), LocalTime.of(10, 0), token, null);

        for (ResponseEntity<String> refused : List.of(foreign, unknown, wrongService)) {
            assertThat(refused.getStatusCode()).as(refused.getBody()).isEqualTo(HttpStatus.NOT_FOUND);
        }
        assertThat(foreign.getBody()).as("foreign vs unknown master: byte-identical 404 (no existence oracle)")
                .isEqualTo(unknown.getBody());
        assertThat(guestRows()).isZero();
    }

    @Test
    @DisplayName("phase 393 audit: an INACTIVE master of the salon → 404 byte-identical to an unknown master, zero rows")
    void should_return404AndWriteNothing_when_salonMasterInactive() throws Exception {
        jdbcTemplate.update("UPDATE masters SET is_active = false WHERE id = ?", masterA);
        String token = guestTokenProvider.generate(guestPhone);

        ResponseEntity<String> inactive = book(masterA, List.of(serviceA), LocalTime.of(10, 0), token, null);
        ResponseEntity<String> unknown = book(UUID.randomUUID(), List.of(serviceA), LocalTime.of(10, 0), token, null);

        assertThat(inactive.getStatusCode()).as(inactive.getBody()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(inactive.getBody()).isEqualTo(unknown.getBody());
        assertThat(guestRows()).isZero();
    }

    // ── per-guest-token booking cap (phase 393 audit, security MEDIUM) ──────────────────────────

    @Test
    @DisplayName("phase 393 audit: ONE guest token books at most 10 times across BOTH routes even from rotating "
            + "IPs — the 11th (salon route) and 12th (master route) are 429 and write nothing")
    void should_return429_when_guestTokenExceedsPerTokenBookingCap() throws Exception {
        String masterSlug = "cap-" + masterA.toString().substring(0, 8) + "-m1";
        jdbcTemplate.update("UPDATE masters SET booking_slug = ? WHERE id = ?", masterSlug, masterA);
        String token = guestTokenProvider.generate(guestPhone);
        for (int hour = 9; hour < 14; hour++) {
            created(postTo(BOOK + masterSlug + "/booking", body(List.of(serviceA), LocalTime.of(hour, 0), null), token));
            created(book(masterB, List.of(serviceB), LocalTime.of(hour, 0), token, null));
        }

        ResponseEntity<String> salonEleventh = book(masterB, List.of(serviceB), LocalTime.of(15, 0), token, null);
        ResponseEntity<String> masterTwelfth =
                postTo(BOOK + masterSlug + "/booking", body(List.of(serviceA), LocalTime.of(15, 0), null), token);
        ResponseEntity<String> freshToken = book(masterB, List.of(serviceB), LocalTime.of(16, 0),
                guestTokenProvider.generate(guestPhone), null);

        assertThat(salonEleventh.getStatusCode()).as(salonEleventh.getBody()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(masterTwelfth.getStatusCode()).as(masterTwelfth.getBody()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(freshToken.getStatusCode()).as("a new OTP (new token) has its own budget")
                .isEqualTo(HttpStatus.CREATED);
        assertThat(guestRows()).isEqualTo(11);
    }

    @Test
    @DisplayName("phase 393 audit: a refused attempt (409) does not burn the token's budget — 10 bookings still fit")
    void should_notCountRefusedAttempt_when_slotTakenBeforeTenBookings() throws Exception {
        created(book(masterB, List.of(serviceB), LocalTime.of(9, 0), guestTokenProvider.generate("+380501112233"), null));
        String token = guestTokenProvider.generate(guestPhone);

        ResponseEntity<String> conflict = book(masterB, List.of(serviceB), LocalTime.of(9, 0), token, null);
        for (int hour = 10; hour < 20; hour++) {
            created(book(masterB, List.of(serviceB), LocalTime.of(hour, 0), token, null));
        }

        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(guestRows()).isEqualTo(10);
    }

    @Test
    @DisplayName("retired salon slug → 410 with a valid guest token, zero rows; inactive salon → 404")
    void should_return410_when_salonSlugRetired() throws Exception {
        String retired = "gone-gsb-" + salon.salonId().toString().substring(0, 6);
        jdbcTemplate.update(
                "INSERT INTO retired_booking_slugs (kind, slug_hash, reason, retired_at) VALUES (?, ?, 'REGENERATED', NOW())",
                BookingLinkKind.SALON.name(), RetiredSlugHasher.hash(BookingLinkKind.SALON, retired));
        String token = guestTokenProvider.generate(guestPhone);

        ResponseEntity<String> gone = postTo(BOOK + "s/" + retired + "/masters/" + masterA + "/booking",
                body(List.of(serviceA), LocalTime.of(10, 0), null), token);
        jdbcTemplate.update("UPDATE salons SET is_active = false WHERE id = ?", salon.salonId());
        ResponseEntity<String> closed = book(masterA, List.of(serviceA), LocalTime.of(10, 0), token, null);

        assertThat(gone.getStatusCode()).as(gone.getBody()).isEqualTo(HttpStatus.GONE);
        assertThat(objectMapper.readTree(gone.getBody()).path("data").path("code").asText())
                .isEqualTo("BOOKING_LINK_RETIRED");
        assertThat(closed.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(guestRows()).isZero();
    }

    @Test
    @DisplayName("no guest token → 401; privacyAccepted=false → 400; comment over 1000 chars → 400; zero rows")
    void should_refuseLikeMasterRoute_when_tokenConsentOrCommentInvalid() throws Exception {
        Map<String, Object> noConsent = body(List.of(serviceA), LocalTime.of(10, 0), null);
        noConsent.put("privacyAccepted", false);

        ResponseEntity<String> noToken = postTo(salonBookingUrl(masterA), body(List.of(serviceA), LocalTime.of(10, 0), null), null);
        ResponseEntity<String> consent = post(masterA, noConsent, guestTokenProvider.generate(guestPhone));
        ResponseEntity<String> longComment = book(masterA, List.of(serviceA), LocalTime.of(10, 0),
                guestTokenProvider.generate(guestPhone), "x".repeat(1001));

        assertThat(noToken.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(consent.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(longComment.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(guestRows()).isZero();
    }

    @Test
    @DisplayName("409 on the second master (slot taken) → the first master's visit stays, the second writes nothing")
    void should_keepFirstVisit_when_secondMasterSlotTaken() throws Exception {
        String token = guestTokenProvider.generate(guestPhone);
        String otherGuest = guestTokenProvider.generate("+380501112233");
        created(book(masterB, List.of(serviceB), LocalTime.of(11, 0), otherGuest, null));

        ResponseEntity<String> first = book(masterA, List.of(serviceA), LocalTime.of(10, 0), token, null);
        ResponseEntity<String> second = book(masterB, List.of(serviceB), LocalTime.of(11, 0), token, null);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).as(second.getBody()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(guestRows()).as("only the first master's booking carries this guest").isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT master_id FROM bookings WHERE guest_phone = ?", UUID.class,
                guestPhone)).isEqualTo(masterA);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    private JsonNode created(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode()).as("body=%s", response.getBody()).isEqualTo(HttpStatus.CREATED);
        return objectMapper.readTree(response.getBody());
    }

    private ResponseEntity<String> book(UUID masterId, List<UUID> masterServiceIds, LocalTime time, String token,
                                        String comment) throws Exception {
        return post(masterId, body(masterServiceIds, time, comment), token);
    }

    private ResponseEntity<String> post(UUID masterId, Map<String, Object> body, String token) throws Exception {
        return postTo(salonBookingUrl(masterId), body, token);
    }

    private ResponseEntity<String> postTo(String url, Map<String, Object> body, String token) throws Exception {
        HttpHeaders headers = ParityRequestId.on(new HttpHeaders());
        headers.set("X-Forwarded-For", PublicBookingPageFixtures.randomIp());
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return restTemplate.exchange(url, HttpMethod.POST,
                new HttpEntity<>(objectMapper.writeValueAsString(body), headers), String.class);
    }

    private Map<String, Object> body(List<UUID> masterServiceIds, LocalTime time, String comment) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (masterServiceIds != null) {
            body.put("masterServiceIds", masterServiceIds.stream().map(UUID::toString).toList());
        }
        body.put("startsAt", ZonedDateTime.of(day, time, KYIV).toOffsetDateTime().toString());
        body.put("name", "Олена");
        body.put("surname", "Коваль");
        body.put("privacyAccepted", true);
        body.put("privacyPolicyVersion", POLICY);
        if (comment != null) {
            body.put("clientComment", comment);
        }
        return body;
    }

    private String salonBookingUrl(UUID masterId) {
        return BOOK + "s/" + salonSlug + "/masters/" + masterId + "/booking";
    }

    private ResponseEntity<String> detail(UUID bookingId, String token) {
        return restTemplate.exchange("/api/v1/bookings/" + bookingId, HttpMethod.GET,
                new HttpEntity<>(fx.bearerHeaders(token)), String.class);
    }

    private ResponseEntity<String> get(String url) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-For", PublicBookingPageFixtures.randomIp());
        return restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private int guestRows() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM bookings WHERE guest_phone = ?", Integer.class,
                guestPhone);
    }
}
