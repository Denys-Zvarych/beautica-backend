package com.beautica.booking;

import com.beautica.auth.phoneotp.GuestTokenProvider;
import com.beautica.booking.dto.GuestBookingResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * Phase 436 — shared fixture for the guest partial-cancel ITs: books a real multi-service LINK visit (or a single
 * booking) through the public API, drives {@code GET / POST /api/v1/book/cancel/{token}} with or without a
 * {@code bookingIds} body, and reads the resulting row state back over JDBC. Every instance gets its own RFC 5737
 * client IP (TEST-NET-1), so its requests have their own per-IP guest-booking / cancel rate-limit buckets.
 */
final class GuestPartialCancelSupport {

    static final String CANCEL_PATH = "/api/v1/book/cancel/";
    static final String GUEST_PHONE = "+380509994361";
    static final String OTHER_GUEST_PHONE = "+380509994362";
    /** A booking-creation note that must never reach an outbox payload (track 25.x). */
    static final String CREATION_NOTE = "Нотатка-436-не-для-outbox";

    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");
    private static final int SERVICE_MINUTES = 30;
    private static final AtomicInteger IP_SEQ = new AtomicInteger(101);

    private final TestRestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final GuestTokenProvider guestTokenProvider;
    private final JdbcTemplate jdbc;
    private final GuestLinkFixtures fixtures;
    private final Clock clock;
    private final String clientIp = "192.0.2." + IP_SEQ.getAndIncrement();

    /** A booked visit: its header, the raw cancel token from the SMS link, and its lines in {@code startsAt} order. */
    record Visit(UUID appointmentId, String token, List<UUID> bookingIds) {
        UUID line(int index) {
            return bookingIds.get(index);
        }
    }

    GuestPartialCancelSupport(TestRestTemplate restTemplate, ObjectMapper objectMapper,
                              GuestTokenProvider guestTokenProvider, JdbcTemplate jdbc,
                              PasswordEncoder passwordEncoder, Clock clock) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.guestTokenProvider = guestTokenProvider;
        this.jdbc = jdbc;
        this.fixtures = new GuestLinkFixtures(jdbc, passwordEncoder, clock);
        this.clock = clock;
    }

    /** An independent master with an open week; returns its booking slug. */
    String masterSlug(String tag) {
        String slug = "p436-" + tag + "-" + Long.toString(System.nanoTime(), 36);
        UUID masterId = fixtures.createMasterWithSlug(slug + "@beautica.test", slug);
        fixtures.seedOpenWeek(masterId);
        return slug;
    }

    /** Master services (30 min each, distinct service types) named exactly {@code names}, in order. */
    List<UUID> services(String slug, List<String> names) {
        UUID masterId = jdbc.queryForObject("SELECT id FROM masters WHERE booking_slug = ?", UUID.class, slug);
        return java.util.stream.IntStream.range(0, names.size())
                .mapToObj(i -> fixtures.createService(masterId, names.get(i), SERVICE_MINUTES, i))
                .toList();
    }

    /** Books {@code masterServiceIds} as one chained LINK visit {@code daysAhead} days out at 10:00 Kyiv. */
    Visit bookVisit(String slug, List<UUID> masterServiceIds, String phone, int daysAhead) throws Exception {
        GuestBookingResponse created = book(slug, Map.of("masterServiceIds", masterServiceIds), phone, daysAhead);
        assertThat(created.appointmentId()).as("several services → one visit header").isNotNull();
        List<UUID> lines = jdbc.queryForList(
                "SELECT id FROM bookings WHERE appointment_id = ? ORDER BY starts_at", UUID.class,
                created.appointmentId());
        return new Visit(created.appointmentId(), tokenOf(created), lines);
    }

    /** Books one service as a legacy single LINK booking (no header); the token's only line is the booking. */
    Visit bookSingle(String slug, UUID masterServiceId, int daysAhead) throws Exception {
        GuestBookingResponse created = book(slug, Map.of("serviceId", masterServiceId), GUEST_PHONE, daysAhead);
        assertThat(created.appointmentId()).as("one service → no visit header").isNull();
        return new Visit(null, tokenOf(created), List.of(created.bookingId()));
    }

    ResponseEntity<String> get(String token) {
        return restTemplate.exchange(CANCEL_PATH + token, HttpMethod.GET, new HttpEntity<>(headers()), String.class);
    }

    /** POST without a body — the pre-436 «cancel everything cancellable». */
    ResponseEntity<String> post(String token) {
        return restTemplate.exchange(CANCEL_PATH + token, HttpMethod.POST, new HttpEntity<>(headers()), String.class);
    }

    /** POST {@code {"bookingIds": ids}}, serialised by Jackson. */
    ResponseEntity<String> post(String token, List<UUID> ids) throws Exception {
        return postJson(token, objectMapper.writeValueAsString(Map.of("bookingIds", ids)));
    }

    ResponseEntity<String> postJson(String token, String json) {
        HttpHeaders headers = headers();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(CANCEL_PATH + token, HttpMethod.POST, new HttpEntity<>(json, headers),
                String.class);
    }

    JsonNode body(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody());
    }

    HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-For", clientIp);
        return headers;
    }

    // ── DB oracles ─────────────────────────────────────────────────────────────

    String status(UUID bookingId) {
        return jdbc.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, bookingId);
    }

    /** Every line's status, in visit order — the «nothing changed» oracle. */
    List<String> statuses(Visit visit) {
        return visit.bookingIds().stream().map(this::status).toList();
    }

    String headerStatus(UUID appointmentId) {
        return jdbc.queryForObject("SELECT status FROM appointments WHERE id = ?", String.class, appointmentId);
    }

    boolean headerHoldsToken(UUID appointmentId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT cancel_token_hash IS NOT NULL FROM appointments WHERE id = ?", Boolean.class, appointmentId));
    }

    void setStatus(UUID bookingId, String status) {
        jdbc.update("UPDATE bookings SET status = ? WHERE id = ?", status, bookingId);
    }

    /** Moves one line to {@code now + minutesAhead} (inside the 2 h cancel window when < 120). */
    void moveStart(UUID bookingId, int minutesAhead) {
        var start = java.time.OffsetDateTime.now(clock).plusMinutes(minutesAhead);
        jdbc.update("UPDATE bookings SET starts_at = ?, ends_at = ? WHERE id = ?",
                start, start.plusMinutes(SERVICE_MINUTES), bookingId);
    }

    // ── internals ──────────────────────────────────────────────────────────────

    private GuestBookingResponse book(String slug, Map<String, Object> services, String phone, int daysAhead)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>(services);
        body.put("startsAt", ZonedDateTime.now(clock.withZone(KYIV)).plusDays(daysAhead)
                .withHour(10).withMinute(0).withSecond(0).withNano(0).toOffsetDateTime().toString());
        body.put("name", "Ірина");
        body.put("surname", "Коваль");
        body.put("privacyAccepted", true);
        body.put("privacyPolicyVersion", "2026-10-20");
        body.put("clientComment", CREATION_NOTE);
        HttpHeaders headers = headers();
        headers.setBearerAuth(guestTokenProvider.generate(phone));
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> resp = restTemplate.exchange("/api/v1/book/" + slug + "/booking", HttpMethod.POST,
                new HttpEntity<>(objectMapper.writeValueAsString(body), headers), String.class);

        assertThat(resp.getStatusCode()).as("guest create, body=%s", resp.getBody()).isEqualTo(HttpStatus.CREATED);
        return objectMapper.readValue(resp.getBody(), GuestBookingResponse.class);
    }

    private static String tokenOf(GuestBookingResponse created) {
        String url = created.cancelUrl();
        return url.substring(url.lastIndexOf('/') + 1);
    }
}
