package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.phoneotp.GuestTokenProvider;
import com.beautica.booking.dto.GuestBookingResponse;
import com.beautica.notification.sms.SmsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review-trust phase 1 (V206) end-to-end over real HTTP + Postgres: a guest cancel token reaches the
 * guest (create response → cancelUrl) but is stored ONLY as its SHA-256. The hash oracle here is
 * Postgres's own {@code sha256()}, NOT {@code CancelTokenHasher}, so an application hasher that drifts
 * from the DB's (backfill / legacy trigger) preimage turns this red instead of agreeing with itself.
 *
 * <p>Each test then drives the public cancel page: GET info → POST cancel (once) → replayed POST and
 * post-consume GET answer a 404 byte-identical (minus the per-request {@code requestId}) to a never-issued
 * token — so the hash-only storage did not open a token-state oracle.
 */
@DisplayName("Guest cancel token hash-only storage (V206) — public API end-to-end, single booking + visit")
class GuestCancelTokenHashEndToEndIT extends AbstractIntegrationTest {

    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");
    private static final String GUEST_PHONE = "+380509998866";
    private static final String CANCEL_PATH = "/api/v1/book/cancel/";

    // Own per-IP bucket per test (RFC 5737 TEST-NET-3): the guest-booking, cancel-POST and the new
    // cancel-info caps are per IP and shared across the cached context.
    private static final AtomicInteger IP_SEQ = new AtomicInteger(151);
    private final String clientIp = "203.0.113." + IP_SEQ.getAndIncrement();

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private GuestTokenProvider guestTokenProvider;

    @Autowired
    private Clock clock;

    @MockBean
    private SmsService smsService;

    private GuestLinkFixtures fixtures;

    @BeforeEach
    void initFixtures() {
        fixtures = new GuestLinkFixtures(jdbcTemplate, passwordEncoder, clock);
    }

    @Test
    @DisplayName("single LINK booking: DB holds only sha256(token); GET info 200; POST cancels once; "
            + "replayed POST and later GET return the unknown-token 404 body")
    void should_storeOnlyHashAndCancelOnce_when_guestSingleBookingCancelledByLink() throws Exception {
        String slug = "hash-e2e-single-" + Long.toString(System.nanoTime(), 36);
        UUID masterId = fixtures.createMasterWithSlug(slug + "@beautica.test", slug);
        fixtures.seedOpenWeek(masterId);
        UUID svc = fixtures.createService(masterId, "Манікюр-хеш", 60, 0);
        ZonedDateTime startsAt = ZonedDateTime.now(clock.withZone(KYIV)).plusDays(3)
                .withHour(10).withMinute(0).withSecond(0).withNano(0);

        GuestBookingResponse created = postBooking(slug, Map.of("serviceId", svc), startsAt);
        String token = tokenOf(created);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT cancel_token, cancel_token_hash, status FROM bookings WHERE id = ?", created.bookingId());
        assertThat(row.get("cancel_token")).as("no plaintext token at rest").isNull();
        assertThat(row.get("cancel_token_hash"))
                .as("the stored form is Postgres sha256 of the token handed to the guest")
                .isEqualTo(pgSha256(token));
        assertThat(rowText("bookings", created.bookingId()))
                .as("the raw token appears in no column of the booking row")
                .doesNotContain(token);

        ResponseEntity<String> info = get(token);
        assertThat(info.getStatusCode()).as(info.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode infoBody = objectMapper.readTree(info.getBody());
        assertThat(infoBody.path("serviceName").asText()).isEqualTo("Манікюр-хеш");
        assertThat(infoBody.path("cancellable").asBoolean()).isTrue();

        assertThat(post(token).getStatusCode()).as("first POST consumes the token").isEqualTo(HttpStatus.NO_CONTENT);
        assertReplayIndistinguishableFromUnknownToken(token);

        Map<String, Object> after = jdbcTemplate.queryForMap(
                "SELECT status, cancel_token_hash FROM bookings WHERE id = ?", created.bookingId());
        assertThat(after.get("status")).isEqualTo("CANCELLED");
        assertThat(after.get("cancel_token_hash")).as("consumed: hash nulled").isNull();
    }

    @Test
    @DisplayName("multi-service LINK visit: header holds only sha256(token), items carry their own distinct "
            + "hashes and no plaintext; GET info 200; POST cancels once; replay 404 = unknown-token 404")
    void should_storeOnlyHashAndCancelOnce_when_guestVisitCancelledByLink() throws Exception {
        String slug = "hash-e2e-visit-" + Long.toString(System.nanoTime(), 36);
        UUID masterId = fixtures.createMasterWithSlug(slug + "@beautica.test", slug);
        fixtures.seedOpenWeek(masterId);
        UUID svc1 = fixtures.createService(masterId, "Візит-хеш-1", 30, 0);
        UUID svc2 = fixtures.createService(masterId, "Візит-хеш-2", 30, 1);
        ZonedDateTime startsAt = ZonedDateTime.now(clock.withZone(KYIV)).plusDays(4)
                .withHour(13).withMinute(0).withSecond(0).withNano(0);

        GuestBookingResponse created = postBooking(slug, Map.of("masterServiceIds", List.of(svc1, svc2)), startsAt);
        assertThat(created.appointmentId()).as("two services → one visit header").isNotNull();
        String token = tokenOf(created);
        String expectedHash = pgSha256(token);

        Map<String, Object> header = jdbcTemplate.queryForMap(
                "SELECT cancel_token, cancel_token_hash FROM appointments WHERE id = ?", created.appointmentId());
        assertThat(header.get("cancel_token")).as("no plaintext token on the header").isNull();
        assertThat(header.get("cancel_token_hash")).isEqualTo(expectedHash);
        assertThat(rowText("appointments", created.appointmentId())).doesNotContain(token);

        List<Map<String, Object>> items = jdbcTemplate.queryForList(
                "SELECT id, cancel_token, cancel_token_hash FROM bookings WHERE appointment_id = ?",
                created.appointmentId());
        assertThat(items).hasSize(2);
        assertThat(items).allSatisfy(item -> {
            assertThat(item.get("cancel_token")).as("no plaintext per-item token").isNull();
            assertThat((String) item.get("cancel_token_hash")).matches("^[0-9a-f]{64}$").isNotEqualTo(expectedHash);
            assertThat(rowText("bookings", (UUID) item.get("id"))).doesNotContain(token);
        });
        assertThat(items.get(0).get("cancel_token_hash"))
                .as("each item mints its own token")
                .isNotEqualTo(items.get(1).get("cancel_token_hash"));

        ResponseEntity<String> info = get(token);
        assertThat(info.getStatusCode()).as(info.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(info.getBody()).path("cancellable").asBoolean()).isTrue();

        assertThat(post(token).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertReplayIndistinguishableFromUnknownToken(token);

        Map<String, Object> headerAfter = jdbcTemplate.queryForMap(
                "SELECT status, cancel_token_hash FROM appointments WHERE id = ?", created.appointmentId());
        assertThat(headerAfter.get("status")).isEqualTo("CANCELLED");
        assertThat(headerAfter.get("cancel_token_hash")).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bookings WHERE appointment_id = ? AND status = 'CANCELLED' "
                        + "AND cancel_token_hash IS NULL", Integer.class, created.appointmentId()))
                .as("both items cancelled and their hashes nulled")
                .isEqualTo(2);
    }

    // ── assertions ─────────────────────────────────────────────────────────────

    private void assertReplayIndistinguishableFromUnknownToken(String consumedToken) throws Exception {
        String unknown = UUID.randomUUID().toString();

        ResponseEntity<String> replayPost = post(consumedToken);
        ResponseEntity<String> unknownPost = post(unknown);
        assertThat(replayPost.getStatusCode()).as("replayed POST").isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(unknownPost.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(stableBody(replayPost))
                .as("a consumed token's POST 404 must be identical to a never-issued token's")
                .isEqualTo(stableBody(unknownPost));

        ResponseEntity<String> replayGet = get(consumedToken);
        ResponseEntity<String> unknownGet = get(unknown);
        assertThat(replayGet.getStatusCode()).as("GET after consume").isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(stableBody(replayGet))
                .as("a consumed token's GET 404 must be identical to a never-issued token's")
                .isEqualTo(stableBody(unknownGet));
    }

    /** The 404 envelope minus {@code requestId}, which legitimately differs per request. */
    private JsonNode stableBody(ResponseEntity<String> response) throws Exception {
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("success").asBoolean(true)).as("an error envelope").isFalse();
        ((ObjectNode) body).remove("requestId");
        return body;
    }

    // ── HTTP ───────────────────────────────────────────────────────────────────

    private GuestBookingResponse postBooking(String slug, Map<String, Object> services, ZonedDateTime startsAt)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>(services);
        body.put("startsAt", startsAt.toOffsetDateTime().toString());
        body.put("name", "Оксана");
        body.put("surname", "Мельник");
        body.put("privacyAccepted", true);
        body.put("privacyPolicyVersion", "2026-10-20");
        HttpHeaders headers = xffHeaders();
        headers.setBearerAuth(guestTokenProvider.generate(GUEST_PHONE));
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> resp = restTemplate.exchange("/api/v1/book/" + slug + "/booking", HttpMethod.POST,
                new HttpEntity<>(objectMapper.writeValueAsString(body), headers), String.class);

        assertThat(resp.getStatusCode()).as("guest create, body=%s", resp.getBody()).isEqualTo(HttpStatus.CREATED);
        return objectMapper.readValue(resp.getBody(), GuestBookingResponse.class);
    }

    private ResponseEntity<String> get(String token) {
        return restTemplate.exchange(CANCEL_PATH + token, HttpMethod.GET, new HttpEntity<>(xffHeaders()), String.class);
    }

    private ResponseEntity<String> post(String token) {
        return restTemplate.exchange(CANCEL_PATH + token, HttpMethod.POST, new HttpEntity<>(xffHeaders()), String.class);
    }

    private HttpHeaders xffHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-For", clientIp);
        return headers;
    }

    private static String tokenOf(GuestBookingResponse created) {
        String url = created.cancelUrl();
        assertThat(url).contains("/book/cancel/");
        String token = url.substring(url.lastIndexOf('/') + 1);
        assertThat(UUID.fromString(token).toString()).as("the link carries the raw UUID token").isEqualTo(token);
        return token;
    }

    // ── DB oracles ─────────────────────────────────────────────────────────────

    private String pgSha256(String token) {
        return jdbcTemplate.queryForObject(
                "SELECT encode(sha256(convert_to(?, 'UTF8')), 'hex')", String.class, token);
    }

    /** The whole row rendered as text — catches the raw token in ANY column, present or future. */
    private String rowText(String table, UUID id) {
        return jdbcTemplate.queryForObject(
                "SELECT t::text FROM " + table + " t WHERE t.id = ?", String.class, id);
    }
}
