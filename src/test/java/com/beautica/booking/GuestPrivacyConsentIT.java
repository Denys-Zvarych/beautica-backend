package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.auth.phoneotp.GuestTokenProvider;
import com.beautica.booking.retention.GuestPiiAnonymisationService;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.beautica.notification.entity.NotificationOutboxEntry;
import com.beautica.notification.repository.NotificationOutboxRepository;
import com.beautica.notification.sms.SmsService;
import com.beautica.support.BookableMasterSeeder;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Phase 381 — guest privacy consent, end to end on real Postgres and the real security chain: persisted on
 * LINK single bookings and on a multi-service visit (header + every child identical), absent on STAFF
 * walk-ins, a hard 400 (no row, no OTP row, no SMS) without consent, kept by anonymisation and by erase,
 * and never in the notification outbox or the logs.
 *
 * <p>The version literal is deliberately unusual so the log/outbox scans cannot match an unrelated date.
 */
@Import(TestSecurityConfig.class)
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("Guest privacy consent — persisted on LINK rows, required on OTP send + booking (Phase 381)")
class GuestPrivacyConsentIT extends AbstractIntegrationTest {

    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");
    private static final String PASSWORD = "Str0ngP@ss1!";
    private static final String VERSION = "2025-02-03.7";
    private static final String GUEST_PHONE = "+380509998877";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private GuestTokenProvider guestTokenProvider;
    @Autowired private GuestPiiAnonymisationService anonymisationService;
    @Autowired private NotificationOutboxRepository outboxRepository;

    @MockBean private SmsService smsService;

    private record Master(UUID userId, UUID masterId, UUID masterServiceId, String slug, String email) {
    }

    private Master bookableIndependentMaster() {
        String email = "m381-" + UUID.randomUUID() + "@beautica.test";
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, first_name, last_name, is_active, email_verified) "
                        + "VALUES (?, ?, ?, 'INDEPENDENT_MASTER', 'Марія', 'Левченко', true, true)",
                userId, email, passwordEncoder.encode(PASSWORD));
        UUID masterId = UUID.randomUUID();
        String slug = "consent-" + Long.toString(System.nanoTime(), 36);
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, booking_slug, created_at, updated_at) "
                        + "VALUES (?, ?, NULL, 'INDEPENDENT_MASTER', true, ?, NOW(), NOW())", masterId, userId, slug);
        UUID first = BookableMasterSeeder.assignNewIndependentService(jdbcTemplate, masterId);
        BookableMasterSeeder.seedUsableSchedule(jdbcTemplate, masterId);
        UUID masterServiceId = jdbcTemplate.queryForObject(
                "SELECT id FROM master_services WHERE service_def_id = ?", UUID.class, first);
        return new Master(userId, masterId, masterServiceId, slug, email);
    }

    private UUID secondService(Master master) {
        UUID def = BookableMasterSeeder.assignNewIndependentService(jdbcTemplate, master.masterId());
        return jdbcTemplate.queryForObject("SELECT id FROM master_services WHERE service_def_id = ?", UUID.class, def);
    }

    private ZonedDateTime slot(int daysAhead, int hour) {
        return ZonedDateTime.now(KYIV).plusDays(daysAhead).withHour(hour).withMinute(0).withSecond(0).withNano(0);
    }

    private HttpHeaders guestHeaders(String phone) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(guestTokenProvider.generate(phone));
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", uniqueClientIp());
        return headers;
    }

    /** The per-IP guest-booking / OTP buckets are shared across the cached context: own IP per call. */
    private static String uniqueClientIp() {
        var random = java.util.concurrent.ThreadLocalRandom.current();
        return "10." + random.nextInt(1, 255) + "." + random.nextInt(1, 255) + "." + random.nextInt(1, 255);
    }

    private ResponseEntity<String> guestBook(Master master, String idsField, ZonedDateTime startsAt, String consent,
                                             String phone) {
        String body = "{" + idsField + ",\"startsAt\":\"" + startsAt.toOffsetDateTime()
                + "\",\"name\":\"Олена\",\"surname\":\"Коваль\"" + consent + "}";
        return restTemplate.exchange("/api/v1/book/" + master.slug() + "/booking", HttpMethod.POST,
                new HttpEntity<>(body, guestHeaders(phone)), String.class);
    }

    private static String consent(boolean accepted) {
        return ",\"privacyAccepted\":" + accepted + ",\"privacyPolicyVersion\":\"" + VERSION + "\"";
    }

    private static String serviceIdField(UUID id) {
        return "\"serviceId\":\"" + id + "\"";
    }

    private static String serviceIdsField(UUID... ids) {
        StringBuilder sb = new StringBuilder("\"masterServiceIds\":[");
        for (int i = 0; i < ids.length; i++) {
            sb.append(i > 0 ? "," : "").append('"').append(ids[i]).append('"');
        }
        return sb.append(']').toString();
    }

    private Map<String, Object> bookingRow(UUID id) {
        return jdbcTemplate.queryForMap("SELECT * FROM bookings WHERE id = ?", id);
    }

    private UUID bookingIdOf(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode()).as("body=%s", response.getBody()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(objectMapper.readTree(response.getBody()).path("bookingId").asText());
    }

    // ── persistence ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("single-service guest booking -> row carries the server-clock acceptance time and the version as sent")
    void should_persistConsent_when_singleServiceGuestBooked() throws Exception {
        Master master = bookableIndependentMaster();
        Instant before = Instant.now();

        UUID bookingId = bookingIdOf(guestBook(master, serviceIdField(master.masterServiceId()), slot(3, 12),
                consent(true), GUEST_PHONE));

        Map<String, Object> row = bookingRow(bookingId);
        Instant acceptedAt = ((java.sql.Timestamp) row.get("guest_privacy_accepted_at")).toInstant();
        assertThat(acceptedAt).isBetween(before.minusSeconds(1), Instant.now().plusSeconds(1));
        assertThat(row.get("guest_privacy_policy_version")).isEqualTo(VERSION);
        assertThat(row.get("booking_source")).isEqualTo("LINK");
    }

    @Test
    @DisplayName("multi-service visit -> the appointment and every child carry identical consent values")
    void should_persistIdenticalConsent_when_multiServiceVisitBooked() throws Exception {
        Master master = bookableIndependentMaster();
        UUID second = secondService(master);

        UUID firstChild = bookingIdOf(guestBook(master, serviceIdsField(master.masterServiceId(), second),
                slot(4, 12), consent(true), GUEST_PHONE));

        UUID appointmentId = jdbcTemplate.queryForObject(
                "SELECT appointment_id FROM bookings WHERE id = ?", UUID.class, firstChild);
        Map<String, Object> header = jdbcTemplate.queryForMap("SELECT * FROM appointments WHERE id = ?", appointmentId);
        List<Map<String, Object>> children = jdbcTemplate.queryForList(
                "SELECT * FROM bookings WHERE appointment_id = ?", appointmentId);
        assertThat(children).hasSize(2);
        assertThat(header.get("guest_privacy_accepted_at")).isNotNull();
        assertThat(header.get("guest_privacy_policy_version")).isEqualTo(VERSION);
        assertThat(children).allSatisfy(child -> {
            assertThat(child.get("guest_privacy_accepted_at")).isEqualTo(header.get("guest_privacy_accepted_at"));
            assertThat(child.get("guest_privacy_policy_version")).isEqualTo(VERSION);
        });
    }

    @Test
    @DisplayName("staff walk-in guest booking -> both consent columns stay NULL")
    void should_leaveConsentNull_when_staffWalkInCreated() throws Exception {
        Master master = bookableIndependentMaster();
        ResponseEntity<String> login = restTemplate.postForEntity("/api/v1/auth/login",
                new LoginRequest(master.email(), PASSWORD), String.class);
        String token = objectMapper.readValue(login.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {})
                .data().accessToken();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"masterServiceIds\":[\"" + master.masterServiceId() + "\"],\"startsAt\":\""
                + slot(5, 12).toOffsetDateTime() + "\",\"guest\":{\"name\":\"Оксана\",\"surname\":\"Гончар\","
                + "\"phone\":\"+380501234567\"}}";

        ResponseEntity<String> response = restTemplate.exchange("/api/v1/masters/" + master.masterId() + "/bookings",
                HttpMethod.POST, new HttpEntity<>(body, headers), String.class);

        assertThat(response.getStatusCode()).as("body=%s", response.getBody()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT * FROM bookings WHERE master_id = ?", master.masterId());
        assertThat(row.get("booking_source")).isEqualTo("STAFF");
        assertThat(row.get("guest_privacy_accepted_at")).isNull();
        assertThat(row.get("guest_privacy_policy_version")).isNull();
    }

    // ── rejection ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("privacyAccepted false or missing on the booking -> 400 and no booking row")
    void should_reject400AndPersistNothing_when_bookingConsentFalseOrMissing() {
        Master master = bookableIndependentMaster();

        ResponseEntity<String> declined = guestBook(master, serviceIdField(master.masterServiceId()), slot(3, 12),
                consent(false), GUEST_PHONE);
        ResponseEntity<String> missing = guestBook(master, serviceIdField(master.masterServiceId()), slot(3, 12),
                "", GUEST_PHONE);

        assertThat(declined.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bookings", Integer.class)).isZero();
    }

    @Test
    @DisplayName("privacyAccepted false or missing on OTP send -> 400, no phone_otps row and no SMS")
    void should_reject400AndSendNothing_when_otpConsentFalseOrMissing() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", uniqueClientIp());
        String phone = "+380671230000";

        ResponseEntity<String> declined = restTemplate.exchange("/api/v1/book/otp/send", HttpMethod.POST,
                new HttpEntity<>("{\"phone\":\"" + phone + "\"" + consent(false) + "}", headers), String.class);
        ResponseEntity<String> missing = restTemplate.exchange("/api/v1/book/otp/send", HttpMethod.POST,
                new HttpEntity<>("{\"phone\":\"" + phone + "\"}", headers), String.class);

        assertThat(declined.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM phone_otps", Integer.class)).isZero();
        verify(smsService, never()).send(anyString(), anyString());
    }

    // ── retention keeps the proof ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("anonymisation scrubs the PII of an aged consented row but keeps both consent columns")
    void should_keepConsent_when_agedConsentedRowAnonymised() throws Exception {
        Master master = bookableIndependentMaster();
        UUID bookingId = bookingIdOf(guestBook(master, serviceIdField(master.masterServiceId()), slot(3, 12),
                consent(true), GUEST_PHONE));
        Object acceptedAt = bookingRow(bookingId).get("guest_privacy_accepted_at");
        jdbcTemplate.update("UPDATE bookings SET status = 'COMPLETED', cancel_token = NULL, "
                + "starts_at = NOW() - interval '14 months', ends_at = NOW() - interval '14 months' + interval '1 hour' "
                + "WHERE id = ?", bookingId);

        anonymisationService.anonymiseDue(Instant.now().minusSeconds(60L * 60 * 24 * 365));

        Map<String, Object> row = bookingRow(bookingId);
        assertThat(row.get("guest_phone")).isNull();
        assertThat(row.get("guest_pii_anonymised_at")).isNotNull();
        assertThat(row.get("guest_privacy_accepted_at")).isEqualTo(acceptedAt);
        assertThat(row.get("guest_privacy_policy_version")).isEqualTo(VERSION);
    }

    @Test
    @DisplayName("the internal erase by phone scrubs the PII but keeps both consent columns")
    void should_keepConsent_when_erasedByPhone() throws Exception {
        Master master = bookableIndependentMaster();
        UUID bookingId = bookingIdOf(guestBook(master, serviceIdField(master.masterServiceId()), slot(3, 12),
                consent(true), GUEST_PHONE));
        Object acceptedAt = bookingRow(bookingId).get("guest_privacy_accepted_at");

        anonymisationService.eraseByPhone(GUEST_PHONE, null);

        Map<String, Object> row = bookingRow(bookingId);
        assertThat(row.get("guest_phone")).isNull();
        assertThat(row.get("guest_pii_anonymised_at")).isNotNull();
        assertThat(row.get("guest_privacy_accepted_at")).isEqualTo(acceptedAt);
        assertThat(row.get("guest_privacy_policy_version")).isEqualTo(VERSION);
    }

    // ── never in outbox / logs / responses ────────────────────────────────────────────────────

    @Test
    @DisplayName("the policy version appears in no outbox payload, no response body and no log line")
    void should_leakConsentNowhere_when_guestBooked(CapturedOutput output) throws Exception {
        Master master = bookableIndependentMaster();

        ResponseEntity<String> response = guestBook(master, serviceIdField(master.masterServiceId()), slot(3, 12),
                consent(true), GUEST_PHONE);

        bookingIdOf(response);
        assertThat(response.getBody()).doesNotContain(VERSION).doesNotContainIgnoringCase("privacy");
        assertThat(outboxRepository.findAll()).isNotEmpty().extracting(NotificationOutboxEntry::getPayload)
                .allSatisfy(payload -> assertThat(payload == null || !payload.contains(VERSION)).isTrue());
        assertThat(output.getAll()).doesNotContain(VERSION);
    }
}
