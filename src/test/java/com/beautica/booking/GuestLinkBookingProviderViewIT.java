package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.phoneotp.GuestTokenProvider;
import com.beautica.config.TestSecurityConfig;
import com.beautica.notification.sms.SmsService;
import com.beautica.support.BookableMasterSeeder;
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

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phases 385 + 386 QA - the guest's comment and phone as the PROVIDER side reads them, for a booking made through
 * the REAL {@code POST /book/{slug}/booking} of a SALON master (the existing 386 matrix seeds rows with SQL, and
 * its one real-endpoint case is an independent master; 385 only reads the independent master's detail).
 *
 * <p>USER RULE under test: the guest phone reaches INDEPENDENT_MASTER (own), SALON_OWNER and the assigned
 * SALON_ADMIN on the booking DETAIL only - never the SALON_MASTER (even performing), never a CLIENT, never a
 * list or an appointment payload. The guest NOTE, by the locked "all notes visible for all sides" decision, is
 * visible to every provider who may open the booking, the performing salon master included.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Guest link booking of a SALON master - comment + phone as each provider role reads them (Phases 385/386)")
class GuestLinkBookingProviderViewIT extends AbstractIntegrationTest {

    private static final String GUEST_PHONE = "+380509871234";
    private static final String GUEST_DIGITS = "509871234";
    private static final String NOTE = "ZQ-NOTE-QA-385 алергія на гель-лак";
    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private GuestTokenProvider guestTokenProvider;
    @MockBean private SmsService smsService;

    private final java.util.Map<String, String> tokens = new java.util.HashMap<>();
    private BookingTestFixtures fx;
    private BookingTestFixtures.SalonFixture salon;
    private String slug;
    private UUID firstService;
    private UUID secondService;
    private String ownerToken; // an EMAIL until owner is first used
    private String adminToken; // an EMAIL until admin is first used
    private String performingMasterToken; // an EMAIL until performingMaster is first used
    private String otherMasterToken; // an EMAIL until otherMaster is first used
    private String foreignAdminToken; // an EMAIL until foreignAdmin is first used
    private String clientToken; // an EMAIL until client is first used

    @BeforeEach
    void setUp() throws Exception {
        tokens.clear();
        fx = new BookingTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
        long n = System.nanoTime();
        salon = fx.createSalon("glb-owner-" + n + "@beautica.test");
        firstService = fx.createSalonService(salon.salonId(), salon.masterId());
        UUID secondDef = BookableMasterSeeder.assignNewSalonService(jdbcTemplate, salon.salonId(), salon.masterId());
        secondService = jdbcTemplate.queryForObject(
                "SELECT id FROM master_services WHERE service_def_id = ?", UUID.class, secondDef);
        BookableMasterSeeder.seedUsableSchedule(jdbcTemplate, salon.masterId());
        slug = "glb-" + salon.masterId().toString().substring(0, 8);
        jdbcTemplate.update("UPDATE masters SET booking_slug = ? WHERE id = ?", slug, salon.masterId());

        ownerToken = salon.ownerEmail();
        performingMasterToken = salon.masterEmail();
        String adminEmail = "glb-admin-" + n + "@beautica.test";
        fx.createUser(adminEmail, "SALON_ADMIN", salon.salonId());
        adminToken = adminEmail;
        var otherSalon = fx.createSalon("glb-other-" + n + "@beautica.test");
        String foreignAdminEmail = "glb-foreign-" + n + "@beautica.test";
        fx.createUser(foreignAdminEmail, "SALON_ADMIN", otherSalon.salonId());
        foreignAdminToken = foreignAdminEmail;
        UUID extra = fx.createExtraSalonMaster(salon.salonId(), "glb-extra");
        otherMasterToken = jdbcTemplate.queryForObject(
                "SELECT u.email FROM users u JOIN masters m ON m.user_id = u.id WHERE m.id = ?", String.class, extra);
        String clientEmail = "glb-client-" + n + "@beautica.test";
        fx.createUser(clientEmail, "CLIENT", null);
        clientToken = clientEmail;
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    private ResponseEntity<String> guestBook(String serviceField, int daysAhead, String commentField) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(guestTokenProvider.generate(GUEST_PHONE));
        headers.setContentType(MediaType.APPLICATION_JSON);
        var random = ThreadLocalRandom.current();
        headers.set("X-Forwarded-For", "10." + random.nextInt(1, 255) + "." + random.nextInt(1, 255) + "."
                + random.nextInt(1, 255));
        String startsAt = ZonedDateTime.now(KYIV).plusDays(daysAhead).withHour(12).withMinute(0).withSecond(0)
                .withNano(0).toOffsetDateTime().toString();
        String body = "{" + serviceField + ",\"startsAt\":\"" + startsAt + "\",\"name\":\"Оксана\",\"surname\":\"Мельник\","
                + "\"privacyAccepted\":true,\"privacyPolicyVersion\":\"2026-10-20\"" + commentField + "}";
        return restTemplate.exchange("/api/v1/book/" + slug + "/booking", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    private JsonNode created(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode()).as("booking body=%s", response.getBody()).isEqualTo(HttpStatus.CREATED);
        return objectMapper.readTree(response.getBody());
    }

    /** {@code who} is the actor's e-mail; the login happens here, on first use, not for every actor in setUp. */
    private ResponseEntity<String> get(String path, String who) {
        String token = tokens.computeIfAbsent(who, email -> {
            try {
                return fx.tokenFor(email);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(fx.bearerHeaders(token)), String.class);
    }

    private JsonNode data(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode()).as("body=%s", response.getBody()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody()).path("data");
    }

    // ── single-service booking ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("real salon-master LINK booking with a comment: owner and admin see comment AND phone; "
            + "the performing salon master sees the comment but NO phone")
    void should_showCommentToAllAndPhoneOnlyToOwnerAdmin_when_guestBooksSalonMasterOverLink() throws Exception {
        UUID id = UUID.fromString(created(guestBook("\"serviceId\":\"" + firstService + "\"", 3,
                ",\"clientComment\":\"" + NOTE + "\"")).path("bookingId").asText());

        for (String token : List.of(ownerToken, adminToken)) {
            JsonNode detail = data(get("/api/v1/bookings/" + id, token));
            assertThat(detail.path("clientComment").asText()).isEqualTo(NOTE);
            assertThat(detail.path("guestPhone").asText()).isEqualTo(GUEST_PHONE);
        }
        ResponseEntity<String> performing = get("/api/v1/bookings/" + id, performingMasterToken);
        assertThat(data(performing).path("clientComment").asText()).isEqualTo(NOTE);
        assertThat(data(performing).path("guestPhone").isNull()).as("body=%s", performing.getBody()).isTrue();
        assertThat(performing.getBody()).doesNotContain(GUEST_DIGITS);
    }

    @Test
    @DisplayName("real salon-master LINK booking: a non-performing salon master, another salon's admin and a CLIENT "
            + "get 403 and neither the phone nor the comment appears in the body")
    void should_refuse403AndLeakNothing_when_nonAudienceOpensRealLinkBooking() throws Exception {
        UUID id = UUID.fromString(created(guestBook("\"serviceId\":\"" + firstService + "\"", 3,
                ",\"clientComment\":\"" + NOTE + "\"")).path("bookingId").asText());

        for (String token : List.of(otherMasterToken, foreignAdminToken, clientToken)) {
            ResponseEntity<String> response = get("/api/v1/bookings/" + id, token);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(String.valueOf(response.getBody())).doesNotContain(GUEST_DIGITS).doesNotContain("ZQ-NOTE-QA-385");
        }
    }

    @Test
    @DisplayName("real salon-master LINK booking: the owner's list and the salon board carry neither phone nor digits")
    void should_keepPhoneOffListsAndBoard_when_realLinkBookingExists() throws Exception {
        UUID id = UUID.fromString(created(guestBook("\"serviceId\":\"" + firstService + "\"", 3, "")).path("bookingId")
                .asText());

        ResponseEntity<String> board = get("/api/v1/bookings/salon/" + salon.salonId() + "?size=50", ownerToken);
        ResponseEntity<String> performingList = get("/api/v1/bookings/me?size=50", performingMasterToken);

        assertThat(board.getStatusCode()).as(board.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(board.getBody()).contains(id.toString()).doesNotContain(GUEST_DIGITS);
        assertThat(performingList.getStatusCode()).as(performingList.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(performingList.getBody()).doesNotContain(GUEST_DIGITS);
    }

    // ── multi-service visit ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("real multi-service LINK visit: the note is on the visit payload for every provider, the visit payload "
            + "never carries the phone, and each child's detail follows the phone rule")
    void should_keepPhoneOffVisitPayloadButOnChildDetail_when_guestBooksVisitOverLink() throws Exception {
        JsonNode visit = created(guestBook("\"masterServiceIds\":[\"" + firstService + "\",\"" + secondService + "\"]", 4,
                ",\"clientComment\":\"" + NOTE + "\""));
        UUID appointmentId = UUID.fromString(visit.path("appointmentId").asText());
        List<UUID> children = jdbcTemplate.queryForList(
                "SELECT id FROM bookings WHERE appointment_id = ?", UUID.class, appointmentId);
        assertThat(children).as("one child booking per service").hasSize(2);

        for (String token : List.of(ownerToken, adminToken, performingMasterToken)) {
            ResponseEntity<String> appointment = get("/api/v1/appointments/" + appointmentId, token);
            assertThat(data(appointment).path("clientComment").asText()).isEqualTo(NOTE);
            assertThat(appointment.getBody()).as("visit payload never carries the guest phone").doesNotContain(GUEST_DIGITS);
        }
        for (UUID child : children) {
            assertThat(data(get("/api/v1/bookings/" + child, ownerToken)).path("guestPhone").asText())
                    .isEqualTo(GUEST_PHONE);
            ResponseEntity<String> asMaster = get("/api/v1/bookings/" + child, performingMasterToken);
            assertThat(asMaster.getBody()).doesNotContain(GUEST_DIGITS);
        }
    }
}
