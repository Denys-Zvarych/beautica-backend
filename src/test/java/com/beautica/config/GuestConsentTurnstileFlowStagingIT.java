package com.beautica.config;

import com.beautica.auth.phoneotp.PhoneOtpVerifyRequest;
import com.beautica.booking.retention.GuestPiiAnonymisationService;
import com.beautica.support.BookableMasterSeeder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phases 381 + 382 + 373 QA — the whole guest journey on the REAL {@code prod,staging} profile (Turnstile ENABLED
 * with Cloudflare's test secret, siteverify stubbed; OTP captured to mail): OTP send with consent and the
 * dummy Turnstile token -> verify the captured code -> book with the guest token and consent -> the booking row
 * stores the consent -> retention anonymisation scrubs the PII but keeps both consent columns.
 *
 * <p>Each phase has IT coverage of its own slice (GuestPrivacyConsentIT runs Turnstile OFF, PhoneOtpTurnstileIT
 * stops at the OTP row); nothing chained them, so a wiring regression BETWEEN the slices (e.g. a Turnstile
 * filter dropping the consent fields, or the guest token no longer carrying a bookable identity) would pass
 * every existing test.
 */
@DisplayName("prod,staging — OTP(consent+Turnstile) -> verify -> booking stores consent -> anonymisation keeps it (381/382/373)")
class GuestConsentTurnstileFlowStagingIT extends AbstractStagingProfileIT {

    private static final String DUMMY_TOKEN = "XXXX.DUMMY.TOKEN.XXXX";
    private static final String TEST_SECRET = "1x0000000000000000000000000000000AA";
    private static final String VERSION = "2026-10-20";
    private static final String SITEVERIFY_PATH = "/turnstile/v0/siteverify";
    private static final Pattern CODE = Pattern.compile("(\\d{6})");
    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private GuestPiiAnonymisationService anonymisationService;
    @MockBean private JavaMailSender mailSender;

    @BeforeEach
    void stubMailAndResetStubs() {
        when(mailSender.createMimeMessage()).thenAnswer(i -> new MimeMessage((Session) null));
        turnstileStub().resetRequests();
    }

    private static String randomPhone() {
        return "+380" + (50 + ThreadLocalRandom.current().nextInt(40))
                + String.format("%07d", ThreadLocalRandom.current().nextInt(10_000_000));
    }

    private static HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var random = ThreadLocalRandom.current();
        headers.set("X-Forwarded-For", "10." + random.nextInt(1, 255) + "." + random.nextInt(1, 255) + "."
                + random.nextInt(1, 255));
        return headers;
    }

    private ResponseEntity<String> post(String path, String body, HttpHeaders headers) {
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private String sendBody(String phone, String turnstileField) {
        return "{\"phone\":\"" + phone + "\",\"privacyAccepted\":true,\"privacyPolicyVersion\":\"" + VERSION + "\""
                + turnstileField + "}";
    }

    private String capturedCode() throws Exception {
        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender, atLeastOnce()).send(sent.capture());
        Matcher code = CODE.matcher(sent.getAllValues().get(0).getContent().toString());
        assertThat(code.find()).as("six-digit OTP in the capture mail").isTrue();
        return code.group(1);
    }

    private record Master(String slug, UUID masterServiceId) {
    }

    private Master bookableIndependentMaster() {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, first_name, last_name, is_active, email_verified) "
                        + "VALUES (?, ?, ?, 'INDEPENDENT_MASTER', 'Марія', 'Левченко', true, true)",
                userId, "m-flow-" + userId + "@beautica.test", new BCryptPasswordEncoder(4).encode("test-password"));
        UUID masterId = UUID.randomUUID();
        String slug = "flow-" + masterId.toString().substring(0, 8);
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, booking_slug, created_at, updated_at) "
                        + "VALUES (?, ?, NULL, 'INDEPENDENT_MASTER', true, ?, NOW(), NOW())", masterId, userId, slug);
        UUID defId = BookableMasterSeeder.assignNewIndependentService(jdbcTemplate, masterId);
        BookableMasterSeeder.seedUsableSchedule(jdbcTemplate, masterId);
        UUID masterServiceId = jdbcTemplate.queryForObject(
                "SELECT id FROM master_services WHERE service_def_id = ?", UUID.class, defId);
        return new Master(slug, masterServiceId);
    }

    @Test
    @DisplayName("full chain: send(consent+dummy token) -> verify captured code -> book -> consent persisted -> anonymise keeps consent")
    void should_keepConsentThroughWholeGuestJourney_when_turnstileEnabled() throws Exception {
        Master master = bookableIndependentMaster();
        String phone = randomPhone();

        ResponseEntity<String> send = post("/api/v1/book/otp/send",
                sendBody(phone, ",\"turnstileToken\":\"" + DUMMY_TOKEN + "\""), jsonHeaders());
        assertThat(send.getStatusCode()).as("send body=%s", send.getBody()).isEqualTo(HttpStatus.OK);

        List<LoggedRequest> siteverifyCalls = turnstileStub().findAll(postRequestedFor(urlEqualTo(SITEVERIFY_PATH)));
        assertThat(siteverifyCalls).as("exactly one siteverify call for one send").hasSize(1);
        assertThat(siteverifyCalls.get(0).getBodyAsString())
                .contains("secret=" + TEST_SECRET).contains("response=" + DUMMY_TOKEN);

        ResponseEntity<String> verified = post("/api/v1/book/otp/verify",
                objectMapper.writeValueAsString(new PhoneOtpVerifyRequest(phone, capturedCode())), jsonHeaders());
        assertThat(verified.getStatusCode()).as("verify body=%s", verified.getBody()).isEqualTo(HttpStatus.OK);
        String guestToken = objectMapper.readTree(verified.getBody()).findValue("guestToken").asText();
        assertThat(guestToken).isNotBlank();

        ZonedDateTime startsAt = ZonedDateTime.now(KYIV).plusDays(3).withHour(12).withMinute(0).withSecond(0).withNano(0);
        HttpHeaders bookingHeaders = jsonHeaders();
        bookingHeaders.setBearerAuth(guestToken);
        Instant before = Instant.now();
        ResponseEntity<String> booked = post("/api/v1/book/" + master.slug() + "/booking",
                "{\"serviceId\":\"" + master.masterServiceId() + "\",\"startsAt\":\"" + startsAt.toOffsetDateTime()
                        + "\",\"name\":\"Олена\",\"surname\":\"Коваль\",\"privacyAccepted\":true,"
                        + "\"privacyPolicyVersion\":\"" + VERSION + "\"}",
                bookingHeaders);
        assertThat(booked.getStatusCode()).as("booking body=%s", booked.getBody()).isEqualTo(HttpStatus.CREATED);
        UUID bookingId = UUID.fromString(objectMapper.readTree(booked.getBody()).findValue("bookingId").asText());

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM bookings WHERE id = ?", bookingId);
        assertThat(row.get("guest_phone")).as("guest phone from the verified token").isEqualTo(phone);
        assertThat(row.get("guest_privacy_policy_version")).isEqualTo(VERSION);
        Instant acceptedAt = ((java.sql.Timestamp) row.get("guest_privacy_accepted_at")).toInstant();
        assertThat(acceptedAt).isBetween(before.minusSeconds(1), Instant.now().plusSeconds(1));

        jdbcTemplate.update("UPDATE bookings SET status = 'COMPLETED', cancel_token = NULL, "
                + "starts_at = NOW() - interval '14 months', ends_at = NOW() - interval '14 months' + interval '1 hour' "
                + "WHERE id = ?", bookingId);
        anonymisationService.anonymiseDue(Instant.now().minusSeconds(60L * 60 * 24 * 365));

        Map<String, Object> scrubbed = jdbcTemplate.queryForMap("SELECT * FROM bookings WHERE id = ?", bookingId);
        assertThat(scrubbed.get("guest_phone")).as("PII scrubbed").isNull();
        assertThat(scrubbed.get("guest_pii_anonymised_at")).isNotNull();
        assertThat(scrubbed.get("guest_privacy_accepted_at")).as("consent proof kept").isEqualTo(row.get("guest_privacy_accepted_at"));
        assertThat(scrubbed.get("guest_privacy_policy_version")).isEqualTo(VERSION);
    }

    @Test
    @DisplayName("missing Turnstile token on staging -> 400, siteverify never called, no phone_otps row, no mail")
    void should_rejectBeforeCloudflareAndStoreNothing_when_tokenMissingOnStaging() {
        String phone = randomPhone();

        ResponseEntity<String> send = post("/api/v1/book/otp/send", sendBody(phone, ""), jsonHeaders());

        assertThat(send.getStatusCode()).as("body=%s", send.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(turnstileStub().findAll(postRequestedFor(urlEqualTo(SITEVERIFY_PATH)))).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM phone_otps WHERE phone = ?", Integer.class, phone))
                .isZero();
        org.mockito.Mockito.verify(mailSender, org.mockito.Mockito.never()).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("consent missing on send with a valid Turnstile token -> 400 and no OTP row (consent gate independent of Turnstile)")
    void should_rejectConsentlessSend_when_turnstileTokenIsValid() {
        String phone = randomPhone();
        String body = "{\"phone\":\"" + phone + "\",\"turnstileToken\":\"" + DUMMY_TOKEN + "\"}";

        ResponseEntity<String> send = post("/api/v1/book/otp/send", body, jsonHeaders());

        assertThat(send.getStatusCode()).as("body=%s", send.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM phone_otps WHERE phone = ?", Integer.class, phone))
                .isZero();
    }
}
