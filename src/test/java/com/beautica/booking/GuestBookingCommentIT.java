package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.phoneotp.GuestTokenProvider;
import com.beautica.booking.PublicBookingPageFixtures.IndependentMaster;
import com.beautica.booking.retention.GuestPiiAnonymisationService;
import com.beautica.config.TestSecurityConfig;
import com.beautica.notification.entity.NotificationOutboxEntry;
import com.beautica.notification.repository.NotificationOutboxRepository;
import com.beautica.notification.sms.SmsService;
import com.beautica.support.BookableMasterSeeder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 385 — the optional «Коментар для майстра» on a guest link booking, over real Postgres and the real
 * security chain: stored where the app stores it (single booking row; multi-service visit HEADER), visible to the
 * provider on the booking detail, bounded exactly like {@code POST /bookings}, absent for old clients, kept out of
 * logs / responses / the notification outbox, and cleared by the phase-373 guest-PII scrub.
 */
@Import(TestSecurityConfig.class)
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("Guest booking comment — «Коментар для майстра» (Phase 385)")
class GuestBookingCommentIT extends AbstractIntegrationTest {

    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");
    private static final String GUEST_PHONE = "+380509998811";
    /** Distinctive so the log / outbox / response scans cannot match anything else. */
    private static final String NOTE = "Буду з дитиною, ZQ-NOTE-385";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private GuestTokenProvider guestTokenProvider;
    @Autowired private GuestPiiAnonymisationService anonymisationService;
    @Autowired private NotificationOutboxRepository outboxRepository;
    @MockBean private SmsService smsService;

    private PublicBookingPageFixtures pages;

    @BeforeEach
    void setUp() {
        pages = new PublicBookingPageFixtures(jdbcTemplate, restTemplate, objectMapper, passwordEncoder);
    }

    private UUID masterServiceOf(UUID serviceDefId) {
        return jdbcTemplate.queryForObject("SELECT id FROM master_services WHERE service_def_id = ?", UUID.class,
                serviceDefId);
    }

    private ZonedDateTime slot(int daysAhead) {
        return ZonedDateTime.now(KYIV).plusDays(daysAhead).withHour(12).withMinute(0).withSecond(0).withNano(0);
    }

    private ResponseEntity<String> book(IndependentMaster master, String idsField, ZonedDateTime startsAt,
                                        String commentField) {
        var random = ThreadLocalRandom.current();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(guestTokenProvider.generate(GUEST_PHONE));
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", "10." + random.nextInt(1, 255) + "." + random.nextInt(1, 255) + "."
                + random.nextInt(1, 255));
        String body = "{" + idsField + ",\"startsAt\":\"" + startsAt.toOffsetDateTime()
                + "\",\"name\":\"Олена\",\"surname\":\"Коваль\",\"privacyAccepted\":true,"
                + "\"privacyPolicyVersion\":\"2025-02-03\"" + commentField + "}";
        return restTemplate.exchange("/api/v1/book/" + master.slug() + "/booking", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    private static String comment(String value) throws Exception {
        return ",\"clientComment\":" + new ObjectMapper().writeValueAsString(value);
    }

    private static String single(UUID masterServiceId) {
        return "\"serviceId\":\"" + masterServiceId + "\"";
    }

    private UUID bookingId(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode()).as("body=%s", response.getBody()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(objectMapper.readTree(response.getBody()).path("bookingId").asText());
    }

    @Test
    @DisplayName("201 with a comment: stored on the booking and returned to the provider on the booking detail; "
            + "the response does not echo it")
    void should_storeAndShowToProvider_when_commentSent() throws Exception {
        IndependentMaster master = pages.independentMaster(true);
        ResponseEntity<String> response = book(master, single(masterServiceOf(master.serviceDefId())), slot(3),
                comment(NOTE));

        UUID id = bookingId(response);

        assertThat(jdbcTemplate.queryForObject("SELECT client_comment FROM bookings WHERE id = ?", String.class, id))
                .isEqualTo(NOTE);
        HttpHeaders provider = new HttpHeaders();
        provider.setBearerAuth(pages.tokenOf(master));
        ResponseEntity<String> detail = restTemplate.exchange("/api/v1/bookings/" + id, HttpMethod.GET,
                new HttpEntity<>(provider), String.class);
        assertThat(detail.getStatusCode()).as(detail.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(detail.getBody()).path("data").path("clientComment").asText())
                .isEqualTo(NOTE);
        assertThat(response.getBody()).doesNotContain("ZQ-NOTE-385");
    }

    @Test
    @DisplayName("a comment over 1000 characters or with control characters is 400 and writes nothing; line breaks "
            + "and tabs are allowed")
    void should_reject400_when_commentTooLongOrHasControlCharacters() throws Exception {
        IndependentMaster master = pages.independentMaster(true);
        String svc = single(masterServiceOf(master.serviceDefId()));

        ResponseEntity<String> tooLong = book(master, svc, slot(3), comment("а".repeat(1001)));
        ResponseEntity<String> nul = book(master, svc, slot(3), comment("до\u0000бре"));
        ResponseEntity<String> bell = book(master, svc, slot(3), comment("до\u0007бре"));

        assertThat(tooLong.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(nul.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(bell.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bookings", Integer.class)).isZero();
        ResponseEntity<String> formatted = book(master, svc, slot(3), comment("рядок 1\nрядок 2\tкінець"));
        UUID id = bookingId(formatted);
        assertThat(jdbcTemplate.queryForObject("SELECT client_comment FROM bookings WHERE id = ?", String.class, id))
                .contains("рядок 1").contains("рядок 2");
        assertThat("а".repeat(1000)).hasSize(1000);
    }

    @Test
    @DisplayName("omitting the field (an old client) or sending a blank one stores NULL and behaves as today")
    void should_storeNull_when_commentOmittedOrBlank() throws Exception {
        IndependentMaster master = pages.independentMaster(true);
        String svc = single(masterServiceOf(master.serviceDefId()));

        UUID omitted = bookingId(book(master, svc, slot(3), ""));
        UUID blank = bookingId(book(master, svc, slot(4), comment("   ")));

        assertThat(jdbcTemplate.queryForObject("SELECT client_comment FROM bookings WHERE id = ?", String.class, omitted))
                .isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT client_comment FROM bookings WHERE id = ?", String.class, blank))
                .isNull();
    }

    @Test
    @DisplayName("multi-service visit: the note is on the appointment header, the child bookings carry none")
    void should_storeNoteOnVisitHeaderOnly_when_multiServiceBooked() throws Exception {
        IndependentMaster master = pages.independentMaster(true);
        UUID second = masterServiceOf(BookableMasterSeeder.assignNewIndependentService(jdbcTemplate, master.masterId()));
        String ids = "\"masterServiceIds\":[\"" + masterServiceOf(master.serviceDefId()) + "\",\"" + second + "\"]";

        UUID firstChild = bookingId(book(master, ids, slot(3), comment(NOTE)));

        UUID appointmentId = jdbcTemplate.queryForObject("SELECT appointment_id FROM bookings WHERE id = ?",
                UUID.class, firstChild);
        assertThat(jdbcTemplate.queryForObject("SELECT client_comment FROM appointments WHERE id = ?", String.class,
                appointmentId)).isEqualTo(NOTE);
        assertThat(jdbcTemplate.queryForList(
                "SELECT client_comment FROM bookings WHERE appointment_id = ?", String.class, appointmentId))
                .hasSize(2).containsOnlyNulls();
    }

    @Test
    @DisplayName("the note appears in no log line and no notification_outbox payload")
    void should_leakNoteNowhere_when_guestBooked(CapturedOutput output) throws Exception {
        IndependentMaster master = pages.independentMaster(true);

        bookingId(book(master, single(masterServiceOf(master.serviceDefId())), slot(3), comment(NOTE)));

        assertThat(output.getAll()).doesNotContain("ZQ-NOTE-385");
        assertThat(outboxRepository.findAll()).isNotEmpty().extracting(NotificationOutboxEntry::getPayload)
                .allSatisfy(payload -> assertThat(payload == null || !payload.contains("ZQ-NOTE-385")).isTrue());
    }

    @Test
    @DisplayName("scrub (373 D6): an aged guest booking and visit lose client_comment, the consent stays")
    void should_nullComment_when_agedGuestRowsAnonymised() throws Exception {
        IndependentMaster master = pages.independentMaster(true);
        UUID bookingId = bookingId(book(master, single(masterServiceOf(master.serviceDefId())), slot(3), comment(NOTE)));
        UUID second = masterServiceOf(BookableMasterSeeder.assignNewIndependentService(jdbcTemplate, master.masterId()));
        UUID visitChild = bookingId(book(master, "\"masterServiceIds\":[\"" + masterServiceOf(master.serviceDefId())
                + "\",\"" + second + "\"]", slot(5), comment(NOTE)));
        UUID appointmentId = jdbcTemplate.queryForObject("SELECT appointment_id FROM bookings WHERE id = ?",
                UUID.class, visitChild);
        jdbcTemplate.update("UPDATE bookings SET status = 'COMPLETED', cancel_token = NULL, "
                + "starts_at = NOW() - interval '14 months', ends_at = NOW() - interval '14 months' + interval '1 hour' "
                + "WHERE id = ?", bookingId);
        jdbcTemplate.update("UPDATE appointments SET status = 'COMPLETED', cancel_token = NULL WHERE id = ?", appointmentId);
        jdbcTemplate.update("UPDATE bookings SET status = 'COMPLETED', cancel_token = NULL, "
                + "starts_at = NOW() - interval '14 months', ends_at = NOW() - interval '14 months' + interval '1 hour' "
                + "WHERE appointment_id = ? AND id = ?", appointmentId, visitChild);
        jdbcTemplate.update("UPDATE bookings SET status = 'COMPLETED', cancel_token = NULL, "
                + "starts_at = NOW() - interval '14 months' + interval '2 hours', "
                + "ends_at = NOW() - interval '14 months' + interval '3 hours' "
                + "WHERE appointment_id = ? AND id <> ?", appointmentId, visitChild);

        anonymisationService.anonymiseDue(Instant.now().minusSeconds(60L * 60 * 24 * 365));

        Map<String, Object> booking = jdbcTemplate.queryForMap("SELECT * FROM bookings WHERE id = ?", bookingId);
        assertThat(booking.get("client_comment")).isNull();
        assertThat(booking.get("guest_privacy_policy_version")).isEqualTo("2025-02-03");
        Map<String, Object> visit = jdbcTemplate.queryForMap("SELECT * FROM appointments WHERE id = ?", appointmentId);
        assertThat(visit.get("client_comment")).isNull();
        assertThat(visit.get("guest_pii_anonymised_at")).isNotNull();
    }

    @Test
    @DisplayName("the GuestBookingResponse JSON is the same shape with or without a note")
    void should_keepResponseShape_when_commentSent() throws Exception {
        IndependentMaster master = pages.independentMaster(true);
        String svc = single(masterServiceOf(master.serviceDefId()));

        JsonNode with = objectMapper.readTree(book(master, svc, slot(3), comment(NOTE)).getBody());
        JsonNode without = objectMapper.readTree(book(master, svc, slot(4), "").getBody());

        assertThat(with.fieldNames()).toIterable().containsExactlyInAnyOrderElementsOf(
                () -> without.fieldNames());
    }
}
