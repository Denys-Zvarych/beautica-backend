package com.beautica.review.invite;

import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.Role;
import com.beautica.common.security.PhoneHasher;
import com.beautica.review.invite.service.ReviewInviteSendJob;
import com.beautica.review.invite.service.ReviewInviteTokenHasher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 401 QA — the guest-review chain as production runs it, with the token taken from the SMS the send job really
 * sent (never minted by the test): provider completes a LINK booking over REST → the AFTER_COMMIT scheduler writes the
 * row → a send-job tick → Turbosms (WireMock) carries {@code beautica.app/r/{token}} → the public review-invite API →
 * the review appears on the PUBLIC master / salon review endpoints (incl. the cached list primed before the submit).
 * Also: opt-out with a live SMS token silences the next visit; a 14-day-old SMS token is a 404 on GET/POST (before and
 * after the expiry sweep) while opt-out still works; a deactivated master's review is stored with a blank card.
 *
 * <p>Same {@code @TestPropertySource} as {@code ReviewInviteSchedulerIT}, so the Spring context is shared. Each request
 * carries its own {@code X-Forwarded-For} so the per-IP buckets never interfere.
 */
@TestPropertySource(properties = {
        "app.booking.sms.enabled=true",
        "app.booking.review-invite.enabled=true",
        "app.booking.review-invite.min-policy-version=2026-10-20"
})
@DisplayName("Guest review — phase 401 QA chain IT (REST completion → SMS token → public review API → public lists)")
class GuestReviewInviteChainIT extends AbstractReviewInviteIT {

    private static final String BASE = "/api/v1/public/review-invites/";
    private static final String REVIEW_LINK_PREFIX = "Оцініть ваш візит: beautica.app/r/";
    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");
    private static final AtomicInteger IP_SEQ = new AtomicInteger();

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private ReviewInviteSendJob job;

    @Autowired
    private PhoneHasher phoneHasher;

    // ── full chain: master ────────────────────────────────────────────────────

    @Test
    @DisplayName("master chain: PATCH complete → tick → SMS token → GET OPEN → POST 201 → public master list (primed "
            + "cache) shows «Марія Ш.» GUEST_LINK, summary + counters updated → POST again 404 → GET ALREADY_REVIEWED")
    void should_publishGuestReviewOnPublicMasterEndpoints_when_smsTokenRedeemed() throws Exception {
        UUID bookingId = fixtures.singleLinkBooking(masterId, masterServiceId, "CONFIRMED", GUEST_PHONE, POLICY, 2);
        ResponseEntity<String> completed = patchAs(masterUserId, Role.INDEPENDENT_MASTER,
                "/api/v1/bookings/" + bookingId + "/complete");
        String token = sendAndCaptureToken();
        ResponseEntity<String> primedList = publicGet("/api/v1/masters/" + masterId + "/reviews");

        ResponseEntity<String> form = get(token);
        ResponseEntity<String> created = submit(token, List.of(item(bookingId, 4, "Дуже акуратно")));
        ResponseEntity<String> list = publicGet("/api/v1/masters/" + masterId + "/reviews");
        ResponseEntity<String> summary = publicGet("/api/v1/masters/" + masterId + "/reviews/summary");
        ResponseEntity<String> again = submit(token, List.of(item(bookingId, 5, null)));
        ResponseEntity<String> formAfter = get(token);

        assertThat(completed.getStatusCode()).as(completed.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(json(primedList).at("/data/data")).as("cache primed empty before the submit").isEmpty();
        assertThat(form.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(form).path("state").asText()).isEqualTo("OPEN");
        assertThat(json(form).path("displayName").asText()).isEqualTo("Марія Ш.");
        assertThat(json(form).path("items")).singleElement()
                .satisfies(i -> assertThat(i.path("bookingId").asText()).isEqualTo(bookingId.toString()));
        assertThat(created.getStatusCode()).as(created.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(json(created).path("created").asInt()).isEqualTo(1);

        JsonNode rows = json(list).at("/data/data");
        assertThat(rows).as("the reviews-by-master entry primed above was evicted").hasSize(1);
        JsonNode row = rows.get(0);
        assertThat(row.path("clientDisplayName").asText()).isEqualTo("Марія Ш.");
        assertThat(row.path("source").asText()).isEqualTo("GUEST_LINK");
        assertThat(row.path("rating").asInt()).isEqualTo(4);
        assertThat(row.path("comment").asText()).isEqualTo("Дуже акуратно");
        assertThat(row.path("masterId").asText()).isEqualTo(masterId.toString());
        assertThat(list.getBody()).doesNotContain("Шевченко").doesNotContain(GUEST_SUBSCRIBER_DIGITS);
        assertThat(json(summary).at("/data/reviewCount").asInt()).isEqualTo(1);
        assertThat(json(summary).at("/data/avgRating").decimalValue()).isEqualByComparingTo("4");
        assertThat(jdbcTemplate.queryForObject("SELECT review_count FROM masters WHERE id = ?", Integer.class,
                masterId)).isEqualTo(1);

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(withoutRequestId(again)).as("consumed = the D2 404 body").isEqualTo(withoutRequestId(get(
                "UnknownChainToken_0001")));
        assertThat(formAfter.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(formAfter).path("state").asText()).isEqualTo("ALREADY_REVIEWED");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM reviews WHERE booking_id = ?", Integer.class,
                bookingId)).isEqualTo(1);
        assertThat(logLines()).as("raw SMS token never logged").noneMatch(line -> line.contains(token));
    }

    // ── full chain: salon-performed visit ─────────────────────────────────────

    @Test
    @DisplayName("salon chain: owner PATCH complete → tick → SMS token → GET providerType SALON with the salon card "
            + "(no title) → POST 201 → public salon list shows «Марія Ш.» GUEST_LINK under the master; summary updated")
    void should_publishGuestReviewOnPublicSalonEndpoints_when_salonVisitTokenRedeemed() throws Exception {
        UUID salonId = affiliateMasterWithSalon();
        UUID ownerId = salonOwnerId(salonId);
        jdbcTemplate.update("UPDATE salons SET avatar_url = ? WHERE id = ?", "https://cdn.beautica.test/s/logo.jpg",
                salonId);
        jdbcTemplate.update("UPDATE users SET professional_title = 'Майстер манікюру' WHERE id = ?", masterUserId);
        UUID bookingId = fixtures.singleLinkBooking(masterId, masterServiceId, "CONFIRMED", GUEST_PHONE, POLICY, 2);
        attachToSalon(salonId, bookingId);
        ResponseEntity<String> completed = patchAs(ownerId, Role.SALON_OWNER,
                "/api/v1/bookings/" + bookingId + "/complete");
        String token = sendAndCaptureToken();
        ResponseEntity<String> primedList = publicGet("/api/v1/salons/" + salonId + "/reviews");

        ResponseEntity<String> form = get(token);
        ResponseEntity<String> created = submit(token, List.of(item(bookingId, 5, null)));
        ResponseEntity<String> list = publicGet("/api/v1/salons/" + salonId + "/reviews");
        ResponseEntity<String> summary = publicGet("/api/v1/salons/" + salonId + "/reviews/summary");

        assertThat(completed.getStatusCode()).as(completed.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(json(primedList).at("/data/data")).isEmpty();
        JsonNode open = json(form);
        assertThat(open.path("providerType").asText()).isEqualTo("SALON");
        assertThat(open.path("providerName").asText()).isEqualTo("Лаванда");
        assertThat(open.path("avatarUrl").asText()).isEqualTo("https://cdn.beautica.test/s/logo.jpg");
        assertThat(open.has("professionalTitle")).as("salon card carries no title").isFalse();
        assertThat(open.has("avgRating")).isFalse();
        assertThat(open.path("reviewCount").asInt()).isZero();
        assertThat(form.getBody()).doesNotContain(salonId.toString()).doesNotContain(masterId.toString());
        assertThat(created.getStatusCode()).as(created.getBody()).isEqualTo(HttpStatus.CREATED);

        JsonNode rows = json(list).at("/data/data");
        assertThat(rows).as("the reviews-by-salon entry primed above was evicted").hasSize(1);
        assertThat(rows.get(0).path("clientDisplayName").asText()).isEqualTo("Марія Ш.");
        assertThat(rows.get(0).path("source").asText()).isEqualTo("GUEST_LINK");
        assertThat(rows.get(0).path("rating").asInt()).isEqualTo(5);
        assertThat(rows.get(0).path("masterFirstName").asText()).isEqualTo("Ірина");
        assertThat(json(summary).at("/data/reviewCount").asInt()).isEqualTo(1);
        assertThat(json(summary).at("/data/avgRating").decimalValue()).isEqualByComparingTo("5");
        assertThat(jdbcTemplate.queryForObject("SELECT review_count FROM salons WHERE id = ?", Integer.class,
                salonId)).isEqualTo(1);
    }

    // ── opt-out with a live SMS token ─────────────────────────────────────────

    @Test
    @DisplayName("opt-out with the unredeemed SMS token → 204; the same phone's next LINK booking completed over REST "
            + "→ invite SKIPPED (OPTED_OUT), a tick sends no second SMS; the first token is still redeemable")
    void should_skipNextInviteAndSendNothing_when_guestOptsOutWithSmsToken() throws Exception {
        UUID first = fixtures.singleLinkBooking(masterId, masterServiceId, "CONFIRMED", GUEST_PHONE, POLICY, 30);
        patchAs(masterUserId, Role.INDEPENDENT_MASTER, "/api/v1/bookings/" + first + "/complete");
        String token = sendAndCaptureToken();

        ResponseEntity<String> optedOut = optOut(token);
        UUID next = fixtures.singleLinkBooking(masterId, masterServiceId, "CONFIRMED", GUEST_PHONE, POLICY, 2);
        ResponseEntity<String> completedNext = patchAs(masterUserId, Role.INDEPENDENT_MASTER,
                "/api/v1/bookings/" + next + "/complete");
        Map<String, Object> nextInvite = jdbcTemplate.queryForMap("SELECT * FROM review_invites WHERE booking_id = ?",
                next);
        job.tick(loudInstantAfter(Instant.now().plus(Duration.ofHours(3))));
        ResponseEntity<String> stillOpen = get(token);

        assertThat(optedOut.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbcTemplate.queryForObject("SELECT EXISTS (SELECT 1 FROM sms_opt_outs WHERE phone_hash = ?)",
                Boolean.class, (Object) phoneHasher.hash(GUEST_PHONE).hash())).isTrue();
        assertThat(completedNext.getStatusCode()).as(completedNext.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(nextInvite.get("status")).isEqualTo("SKIPPED");
        assertThat(nextInvite.get("skip_reason")).isEqualTo("OPTED_OUT");
        assertThat(nextInvite.get("token_hash")).isNull();
        assertThat(turbosmsRequests()).as("only the first visit's SMS").hasSize(1);
        assertThat(json(stillOpen).path("state").asText()).as("opt-out does not spend the token")
                .isEqualTo("OPEN");
    }

    // ── 14-day expiry of a real SMS token ─────────────────────────────────────

    @Test
    @DisplayName("SMS token sent 15 days ago: GET and POST → the identical 404 both while still SENT (past expires_at) "
            + "and after the sweep marks it EXPIRED; opt-out still → 204 (within 30 days of the send)")
    void should_404ReadAndSubmitButAllowOptOut_when_smsTokenPastFourteenDays() throws Exception {
        UUID bookingId = fixtures.singleLinkBooking(masterId, masterServiceId, "COMPLETED", GUEST_PHONE, POLICY, 5);
        Instant sentAt = LocalDate.now(KYIV).minusDays(15).atTime(12, 0).atZone(KYIV).toInstant();
        UUID inviteId = scheduledBookingInvite(bookingId, phoneHasher.hash(GUEST_PHONE).hash(),
                sentAt.minus(Duration.ofHours(1)));
        job.tick(sentAt);
        String token = singleSmsToken();
        Map<String, Object> afterSend = inviteRow(inviteId);
        JsonNode unknownBody = withoutRequestId(get("UnknownChainToken_0002"));

        ResponseEntity<String> readSent = get(token);
        ResponseEntity<String> postSent = submit(token, List.of(item(bookingId, 5, null)));
        job.tick(Instant.now());
        String statusAfterSweep = (String) inviteRow(inviteId).get("status");
        ResponseEntity<String> readExpired = get(token);
        ResponseEntity<String> postExpired = submit(token, List.of(item(bookingId, 5, null)));
        ResponseEntity<String> optedOut = optOut(token);

        assertThat(afterSend.get("status")).isEqualTo("SENT");
        assertThat(((Timestamp) afterSend.get("expires_at")).toInstant())
                .isEqualTo(sentAt.plus(Duration.ofDays(14))).isBefore(Instant.now());
        assertThat(statusAfterSweep).isEqualTo("EXPIRED");
        for (ResponseEntity<String> response : List.of(readSent, postSent, readExpired, postExpired)) {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(withoutRequestId(response)).isEqualTo(unknownBody);
        }
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM reviews WHERE booking_id = ?", Integer.class,
                bookingId)).isZero();
        assertThat(optedOut.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM sms_opt_outs WHERE phone_hash = ?",
                Integer.class, (Object) phoneHasher.hash(GUEST_PHONE).hash())).isEqualTo(1);
    }

    // ── hidden master ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("master deactivated after the SMS: GET OPEN with a blank card → POST 201, GUEST_LINK row stored and "
            + "the master's counters recalculated; a later form for the same master keeps the card blank")
    void should_storeReviewWithBlankCard_when_masterDeactivatedAfterSms() throws Exception {
        jdbcTemplate.update("UPDATE users SET avatar_url = ?, professional_title = ? WHERE id = ?",
                "https://cdn.beautica.test/u/avatar.jpg", "Майстер манікюру", masterUserId);
        UUID bookingId = fixtures.singleLinkBooking(masterId, masterServiceId, "CONFIRMED", GUEST_PHONE, POLICY, 2);
        patchAs(masterUserId, Role.INDEPENDENT_MASTER, "/api/v1/bookings/" + bookingId + "/complete");
        String token = sendAndCaptureToken();
        jdbcTemplate.update("UPDATE masters SET is_active = false WHERE id = ?", masterId);
        UUID laterBooking = fixtures.singleLinkBooking(masterId, masterServiceId, "COMPLETED", "+380503334455",
                POLICY, 1);
        String laterToken = "HiddenMasterLaterTok_1";
        sentBookingInvite(laterBooking, laterToken);

        ResponseEntity<String> form = get(token);
        ResponseEntity<String> created = submit(token, List.of(item(bookingId, 3, "Нормально")));
        ResponseEntity<String> laterForm = get(laterToken);

        assertBlankCard(form);
        assertThat(json(form).path("providerName").asText()).isEqualTo("Ірина Коваль");
        assertThat(created.getStatusCode()).as(created.getBody()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> review = jdbcTemplate.queryForMap("SELECT * FROM reviews WHERE booking_id = ?", bookingId);
        assertThat(review.get("source")).isEqualTo("GUEST_LINK");
        assertThat(review.get("client_id")).isNull();
        assertThat(review.get("master_id")).isEqualTo(masterId);
        assertThat(review.get("guest_display_name")).isEqualTo("Марія Ш.");
        assertThat(jdbcTemplate.queryForObject("SELECT review_count FROM masters WHERE id = ?", Integer.class,
                masterId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT avg_rating FROM masters WHERE id = ?", BigDecimal.class,
                masterId)).isEqualByComparingTo("3");
        assertBlankCard(laterForm);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void assertBlankCard(ResponseEntity<String> form) throws Exception {
        assertThat(form.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = json(form);
        assertThat(body.path("state").asText()).isEqualTo("OPEN");
        assertThat(body.path("reviewCount").asInt()).isZero();
        assertThat(body.has("avgRating")).isFalse();
        assertThat(body.has("avatarUrl")).isFalse();
        assertThat(body.has("professionalTitle")).isFalse();
    }

    /** Ticks the job at the single invite's {@code send_after} (and a minute later) and returns the SMS token. */
    private String sendAndCaptureToken() throws Exception {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM review_invites");
        assertThat(rows).as("exactly one invite after the completion").hasSize(1);
        Instant sendAfter = ((Timestamp) rows.get(0).get("send_after")).toInstant();
        job.tick(sendAfter);
        job.tick(sendAfter.plusSeconds(60));
        return singleSmsToken();
    }

    private String singleSmsToken() throws Exception {
        List<LoggedRequest> requests = turbosmsRequests();
        assertThat(requests).as("exactly one SMS").hasSize(1);
        assertThat(payload(requests.get(0)).path("recipients").get(0).asText()).isEqualTo(GUEST_PHONE);
        String text = payload(requests.get(0)).path("sms").path("text").asText();
        assertThat(text).startsWith(REVIEW_LINK_PREFIX);
        return text.substring(REVIEW_LINK_PREFIX.length());
    }

    /** The first instant at or after {@code from} that is outside the Kyiv quiet window (12:00 Kyiv if needed). */
    private static Instant loudInstantAfter(Instant from) {
        int hour = from.atZone(KYIV).getHour();
        if (hour >= 10 && hour < 20) {
            return from;
        }
        LocalDate day = from.atZone(KYIV).toLocalDate();
        return (hour >= 20 ? day.plusDays(1) : day).atTime(12, 0).atZone(KYIV).toInstant();
    }

    private void sentBookingInvite(UUID bookingId, String token) {
        Instant now = Instant.now();
        jdbcTemplate.update("INSERT INTO review_invites (id, booking_id, phone_hash, token_hash, status, send_after, "
                        + "sent_at, expires_at, created_at) VALUES (?, ?, ?, ?, 'SENT', ?, ?, ?, ?)",
                UUID.randomUUID(), bookingId, phoneHasher.hash("+380503334455").hash(),
                ReviewInviteTokenHasher.hash(token), Timestamp.from(now.minus(Duration.ofHours(1))),
                Timestamp.from(now.minus(Duration.ofHours(1))), Timestamp.from(now.plus(Duration.ofDays(14))),
                Timestamp.from(now.minus(Duration.ofHours(1))));
    }

    private ResponseEntity<String> patchAs(UUID userId, Role role, String path) {
        String email = jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtTokenProvider.generateAccessToken(userId, email, role));
        return restTemplate.exchange(path, HttpMethod.PATCH, new HttpEntity<>(headers), String.class);
    }

    private static HttpHeaders visitorHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        int n = IP_SEQ.incrementAndGet();
        headers.set("X-Forwarded-For", "198.20." + (n / 250) + "." + (n % 250 + 1));
        return headers;
    }

    private ResponseEntity<String> publicGet(String path) {
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(visitorHeaders()), String.class);
    }

    private ResponseEntity<String> get(String token) {
        return publicGet(BASE + token);
    }

    private ResponseEntity<String> submit(String token, List<Map<String, Object>> items) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("turnstileToken", "t", "items", items));
        return restTemplate.exchange(BASE + token + "/reviews", HttpMethod.POST,
                new HttpEntity<>(body, visitorHeaders()), String.class);
    }

    private ResponseEntity<String> optOut(String token) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("turnstileToken", "t"));
        return restTemplate.exchange(BASE + token + "/opt-out", HttpMethod.POST,
                new HttpEntity<>(body, visitorHeaders()), String.class);
    }

    private static Map<String, Object> item(UUID bookingId, int rating, String comment) {
        return comment == null
                ? Map.of("bookingId", bookingId.toString(), "rating", rating)
                : Map.of("bookingId", bookingId.toString(), "rating", rating, "comment", comment);
    }

    private JsonNode json(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody());
    }

    private JsonNode withoutRequestId(ResponseEntity<String> response) throws Exception {
        ObjectNode node = (ObjectNode) objectMapper.readTree(response.getBody());
        node.remove("requestId");
        return node;
    }
}
