package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.config.TestSecurityConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 386 - the guest's phone on {@code GET /bookings/{id}} for the provider side, and the HARD RULE that a
 * REGISTERED user's phone never reaches any provider on any surface. Real Postgres, real security chain.
 *
 * <p>The raw digits are scanned for in whole response bodies (not just the field), so a leak through any other
 * field, list row or error body is caught too.
 */
@Import(TestSecurityConfig.class)
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("Guest phone is provider-visible on booking detail only; a registered user's phone never is (Phase 386)")
class GuestPhoneProviderVisibilityIT extends AbstractIntegrationTest {

    private static final String GUEST_PHONE = "+380671234567";
    private static final String GUEST_DIGITS = "671234567";
    private static final String ACCOUNT_PHONE = "+380991112233";
    private static final String ACCOUNT_DIGITS = "991112233";
    private static final String BOOKINGS = "/api/v1/bookings";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private com.beautica.auth.phoneotp.GuestTokenProvider guestTokenProvider;
    @Autowired private com.beautica.notification.repository.NotificationOutboxRepository outboxRepository;
    @org.springframework.boot.test.mock.mockito.MockBean private com.beautica.notification.sms.SmsService smsService;

    private BookingTestFixtures fx;
    private BookingTestFixtures.SalonFixture salon;
    private UUID masterServiceId;
    private String ownerToken;
    private String performingMasterToken;
    private String adminToken;
    private String foreignAdminToken;
    private String nonPerformingMasterToken;
    private String guestlessClientToken;
    private String registeredClientToken;
    private UUID registeredClientId;
    private UUID ownerUserId;

    @BeforeEach
    void setUp() throws Exception {
        fx = new BookingTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
        long n = System.nanoTime();
        salon = fx.createSalon("gp-owner-" + n + "@beautica.test");
        masterServiceId = fx.createSalonService(salon.salonId(), salon.masterId());
        ownerToken = fx.tokenFor(salon.ownerEmail());
        performingMasterToken = fx.tokenFor(salon.masterEmail());
        ownerUserId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, salon.ownerEmail());

        String adminEmail = "gp-admin-" + n + "@beautica.test";
        fx.createUser(adminEmail, "SALON_ADMIN", salon.salonId());
        adminToken = fx.tokenFor(adminEmail);

        var otherSalon = fx.createSalon("gp-other-owner-" + n + "@beautica.test");
        String foreignAdminEmail = "gp-foreign-admin-" + n + "@beautica.test";
        fx.createUser(foreignAdminEmail, "SALON_ADMIN", otherSalon.salonId());
        foreignAdminToken = fx.tokenFor(foreignAdminEmail);

        UUID extraMaster = fx.createExtraSalonMaster(salon.salonId(), "gp-extra");
        nonPerformingMasterToken = fx.tokenFor(jdbcTemplate.queryForObject(
                "SELECT u.email FROM users u JOIN masters m ON m.user_id = u.id WHERE m.id = ?", String.class,
                extraMaster));

        String strangerEmail = "gp-stranger-" + n + "@beautica.test";
        fx.createUser(strangerEmail, "CLIENT", null);
        guestlessClientToken = fx.tokenFor(strangerEmail);

        String clientEmail = "gp-client-" + n + "@beautica.test";
        registeredClientId = fx.createUser(clientEmail, "CLIENT", null);
        jdbcTemplate.update("UPDATE users SET phone_number = ? WHERE id = ?", ACCOUNT_PHONE, registeredClientId);
        registeredClientToken = fx.tokenFor(clientEmail);
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────

    private UUID insertLinkGuestBooking() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, master_id, master_service_id, salon_id, status, starts_at, ends_at, "
                        + "price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, booking_source, "
                        + "guest_name, guest_surname, guest_phone, cancel_token, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'CONFIRMED', NOW() + interval '2 days', NOW() + interval '2 days 1 hour', "
                        + "500.00, 60, 0, 'LINK', 'Оксана', 'Мельник', ?, ?, NOW(), NOW())",
                id, salon.masterId(), masterServiceId, salon.salonId(), GUEST_PHONE, UUID.randomUUID());
        return id;
    }

    private UUID insertWalkIn() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, master_id, master_service_id, salon_id, status, starts_at, ends_at, "
                        + "price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, booking_source, "
                        + "guest_name, guest_surname, guest_phone, created_by_user_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'CONFIRMED', NOW() + interval '3 days', NOW() + interval '3 days 1 hour', "
                        + "500.00, 60, 0, 'STAFF', 'Марія', 'Левченко', ?, ?, NOW(), NOW())",
                id, salon.masterId(), masterServiceId, salon.salonId(), GUEST_PHONE, ownerUserId);
        return id;
    }

    private UUID insertRegisteredBooking(String source) {
        UUID id = UUID.randomUUID();
        String createdBy = "STAFF".equals(source) ? ", created_by_user_id" : "";
        String createdByValue = "STAFF".equals(source) ? ", ?" : "";
        List<Object> args = new ArrayList<>(List.of(id, registeredClientId, salon.masterId(), masterServiceId,
                salon.salonId(), source));
        if ("STAFF".equals(source)) {
            args.add(ownerUserId);
        }
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, starts_at, "
                        + "ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, "
                        + "booking_source" + createdBy + ", created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'CONFIRMED', NOW() + interval '" + ("STAFF".equals(source) ? 6 : 4)
                        + " days', NOW() + interval '" + ("STAFF".equals(source) ? 6 : 4) + " days 1 hour', "
                        + "500.00, 60, 0, ?" + createdByValue + ", NOW(), NOW())",
                args.toArray());
        return id;
    }

    private ResponseEntity<String> get(String url, String token) {
        return restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(fx.bearerHeaders(token)), String.class);
    }

    private ResponseEntity<String> detail(UUID bookingId, String token) {
        return get(BOOKINGS + "/" + bookingId, token);
    }

    private JsonNode data(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode()).as("body=%s", response.getBody()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody()).path("data");
    }

    private static void assertNoDigits(String body, String... digits) {
        for (String d : digits) {
            assertThat(body).as("response must not contain %s", d).doesNotContain(d);
        }
    }

    // ── guest bookings: the phone reaches exactly the provider side ───────────────────────────

    @Test
    @DisplayName("LINK guest booking: the salon owner and the assigned admin see the phone on detail")
    void should_showPhone_when_ownerOrAdminOpensLinkGuestBookingDetail() throws Exception {
        UUID id = insertLinkGuestBooking();

        for (String token : List.of(ownerToken, adminToken)) {
            assertThat(data(detail(id, token)).path("guestPhone").asText()).isEqualTo(GUEST_PHONE);
        }
    }

    @Test
    @DisplayName("USER RULE: the PERFORMING salon master gets 200 but NO phone - null, and the digits are nowhere in the body")
    void should_hidePhone_when_performingSalonMasterOpensGuestBooking() throws Exception {
        for (UUID id : List.of(insertLinkGuestBooking(), insertWalkIn())) {
            ResponseEntity<String> response = detail(id, performingMasterToken);

            assertThat(data(response).path("guestPhone").isNull()).as("body=%s", response.getBody()).isTrue();
            assertNoDigits(response.getBody(), GUEST_DIGITS);
        }
    }

    @Test
    @DisplayName("staff walk-in (client_id NULL): the owner and the assigned admin see the phone the staff member typed")
    void should_showPhone_when_ownerOrAdminOpensWalkInDetail() throws Exception {
        UUID id = insertWalkIn();

        for (String token : List.of(ownerToken, adminToken)) {
            assertThat(data(detail(id, token)).path("guestPhone").asText()).isEqualTo(GUEST_PHONE);
        }
    }

    @Test
    @DisplayName("performing INDEPENDENT master sees the phone of their guest booking")
    void should_showPhone_when_independentMasterOpensTheirGuestBooking() throws Exception {
        String email = "gp-indep-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fx.createIndependentMaster(email);
        UUID serviceId = fx.createIndependentMasterService(masterId);
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, master_id, master_service_id, status, starts_at, ends_at, "
                        + "price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, booking_source, "
                        + "guest_name, guest_phone, cancel_token, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'CONFIRMED', NOW() + interval '2 days', NOW() + interval '2 days 1 hour', "
                        + "500.00, 60, 0, 'LINK', 'Оксана', ?, ?, NOW(), NOW())",
                id, masterId, serviceId, GUEST_PHONE, UUID.randomUUID());

        assertThat(data(detail(id, fx.tokenFor(email))).path("guestPhone").asText()).isEqualTo(GUEST_PHONE);
    }

    @Test
    @DisplayName("non-performing salon master, an admin of ANOTHER salon and a stranger CLIENT are refused (403) and never see the digits")
    void should_refuse403AndLeakNothing_when_nonAudienceOpensGuestBooking() {
        UUID id = insertLinkGuestBooking();

        for (String token : List.of(nonPerformingMasterToken, foreignAdminToken, guestlessClientToken)) {
            ResponseEntity<String> response = detail(id, token);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertNoDigits(String.valueOf(response.getBody()), GUEST_DIGITS);
        }
    }

    @Test
    @DisplayName("lists and the board never carry the guest phone: GET /bookings/me (provider) and GET /bookings/salon/{id}")
    void should_keepPhoneOffEveryList_when_guestBookingListed() throws Exception {
        UUID link = insertLinkGuestBooking();
        UUID walkIn = insertWalkIn();

        List<ResponseEntity<String>> lists = List.of(
                get(BOOKINGS + "/me?size=50", performingMasterToken),
                get(BOOKINGS + "/salon/" + salon.salonId() + "?size=50", ownerToken),
                get(BOOKINGS + "/salon/" + salon.salonId() + "?size=50", adminToken));

        for (ResponseEntity<String> list : lists) {
            assertThat(list.getStatusCode()).as("body=%s", list.getBody()).isEqualTo(HttpStatus.OK);
            assertThat(list.getBody()).contains(link.toString()).contains(walkIn.toString());
            assertNoDigits(list.getBody(), GUEST_DIGITS);
            objectMapper.readTree(list.getBody()).findValues("guestPhone")
                    .forEach(v -> assertThat(v.isNull()).as("list guestPhone must be null").isTrue());
        }
    }

    // ── registered users: the HARD RULE ───────────────────────────────────────────────────────

    @Test
    @DisplayName("HARD RULE: a registered client's APP and STAFF bookings show NO phone to any provider role on detail, "
            + "and the account's digits appear nowhere in the body")
    void should_neverShowRegisteredClientsPhone_when_anyProviderOpensDetail() throws Exception {
        for (String source : List.of("APP", "STAFF")) {
            UUID id = insertRegisteredBooking(source);

            for (String token : List.of(performingMasterToken, ownerToken, adminToken)) {
                ResponseEntity<String> response = detail(id, token);
                assertThat(data(response).path("guestPhone").isNull()).as("%s detail", source).isTrue();
                assertNoDigits(response.getBody(), ACCOUNT_DIGITS, GUEST_DIGITS);
            }
        }
    }

    @Test
    @DisplayName("HARD RULE: the registered client's phone is on no list/board row a provider reads")
    void should_neverShowRegisteredClientsPhone_when_anyProviderListsBookings() throws Exception {
        UUID app = insertRegisteredBooking("APP");
        UUID staff = insertRegisteredBooking("STAFF");

        List<ResponseEntity<String>> lists = List.of(
                get(BOOKINGS + "/me?size=50", performingMasterToken),
                get(BOOKINGS + "/salon/" + salon.salonId() + "?size=50", ownerToken),
                get(BOOKINGS + "/salon/" + salon.salonId() + "?size=50", adminToken));

        for (ResponseEntity<String> list : lists) {
            assertThat(list.getStatusCode()).as("body=%s", list.getBody()).isEqualTo(HttpStatus.OK);
            assertThat(list.getBody()).contains(app.toString()).contains(staff.toString());
            assertNoDigits(list.getBody(), ACCOUNT_DIGITS);
            objectMapper.readTree(list.getBody()).findValues("guestPhone")
                    .forEach(v -> assertThat(v.isNull()).isTrue());
        }
    }

    @Test
    @DisplayName("a CLIENT on their own booking gets guestPhone null and their own phone is not echoed")
    void should_notEchoClientsOwnPhone_when_clientOpensOwnBooking() throws Exception {
        UUID id = insertRegisteredBooking("APP");

        ResponseEntity<String> response = detail(id, registeredClientToken);

        assertThat(data(response).path("guestPhone").isNull()).isTrue();
        assertNoDigits(response.getBody(), ACCOUNT_DIGITS);
        ResponseEntity<String> mine = get(BOOKINGS + "/me?size=50", registeredClientToken);
        assertNoDigits(mine.getBody(), ACCOUNT_DIGITS);
        objectMapper.readTree(mine.getBody()).findValues("guestPhone").forEach(v -> assertThat(v.isNull()).isTrue());
    }

    // ── logs ─────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("reading the detail never writes the phone to a log line")
    void should_neverLogPhone_when_detailRead(CapturedOutput output) throws Exception {
        UUID id = insertLinkGuestBooking();

        data(detail(id, ownerToken));

        assertThat(output.getAll()).doesNotContain(GUEST_PHONE).doesNotContain(GUEST_DIGITS);
    }

    @Test
    @DisplayName("a REAL link booking: the independent master sees the phone on detail, and the phone is in no "
            + "notification_outbox payload and no log line")
    void should_keepPhoneOutOfOutboxAndLogs_when_realGuestBookingCreated(CapturedOutput output) throws Exception {
        var pages = new PublicBookingPageFixtures(jdbcTemplate, restTemplate, objectMapper, passwordEncoder);
        var master = pages.independentMaster(true);
        UUID masterServiceId = jdbcTemplate.queryForObject(
                "SELECT id FROM master_services WHERE service_def_id = ?", UUID.class, master.serviceDefId());
        var headers = new org.springframework.http.HttpHeaders();
        headers.setBearerAuth(guestTokenProvider.generate(GUEST_PHONE));
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", "10.7.7." + (1 + (int) (System.nanoTime() % 200)));
        String startsAt = java.time.ZonedDateTime.now(java.time.ZoneId.of("Europe/Kyiv")).plusDays(3).withHour(12)
                .withMinute(0).withSecond(0).withNano(0).toOffsetDateTime().toString();
        String body = "{\"serviceId\":\"" + masterServiceId + "\",\"startsAt\":\"" + startsAt
                + "\",\"name\":\"Олена\",\"surname\":\"Коваль\",\"privacyAccepted\":true,"
                + "\"privacyPolicyVersion\":\"2025-02-03\"}";

        ResponseEntity<String> created = restTemplate.exchange("/api/v1/book/" + master.slug() + "/booking",
                HttpMethod.POST, new HttpEntity<>(body, headers), String.class);

        assertThat(created.getStatusCode()).as(created.getBody()).isEqualTo(HttpStatus.CREATED);
        UUID bookingId = UUID.fromString(objectMapper.readTree(created.getBody()).path("bookingId").asText());
        assertThat(data(detail(bookingId, pages.tokenOf(master))).path("guestPhone").asText()).isEqualTo(GUEST_PHONE);
        assertThat(created.getBody()).as("the create response never echoes the phone").doesNotContain(GUEST_DIGITS);
        assertThat(outboxRepository.findAll()).isNotEmpty().allSatisfy(entry ->
                assertThat(String.valueOf(entry.getPayload())).doesNotContain(GUEST_DIGITS));
        assertThat(output.getAll()).doesNotContain(GUEST_PHONE).doesNotContain(GUEST_DIGITS);
    }
}
