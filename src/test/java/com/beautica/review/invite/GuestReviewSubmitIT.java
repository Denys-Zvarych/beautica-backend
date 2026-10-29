package com.beautica.review.invite;

import com.beautica.common.security.PhoneHasher;
import com.beautica.review.invite.dto.ReviewInviteResponse;
import com.beautica.review.invite.service.GuestReviewService;
import com.beautica.review.invite.service.ReviewInviteTokenHasher;
import com.beautica.support.HibernateStatistics;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.stat.Statistics;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 401 — the guest review page's API end to end over HTTP against real Postgres: read, submit (visit- and
 * booking-keyed, D8), the one-submit rule (D4), the identical 404 (D2), the shared create path (D3: GUEST_LINK row,
 * rating recalculated, provider notified), and the token never reaching a log line. Every request carries its own
 * {@code X-Forwarded-For} so the per-IP buckets (pinned by {@code ReviewInviteRateLimitTest}) never interfere.
 *
 * <p>Same {@code @TestPropertySource} as {@code ReviewInviteSchedulerIT}, so the Spring context is shared.
 */
@TestPropertySource(properties = {
        "app.booking.sms.enabled=true",
        "app.booking.review-invite.enabled=true",
        "app.booking.review-invite.min-policy-version=2026-10-20"
})
@DisplayName("Guest review submit — phase 401 IT")
class GuestReviewSubmitIT extends AbstractReviewInviteIT {

    private static final String BASE = "/api/v1/public/review-invites/";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final AtomicInteger IP_SEQ = new AtomicInteger();
    private static final com.fasterxml.jackson.databind.ObjectMapper ITEM_JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private PhoneHasher phoneHasher;

    @Autowired
    private GuestReviewService guestReviewService;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    // ── helpers ────────────────────────────────────────────────────────────────

    private static String newToken() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        int n = IP_SEQ.incrementAndGet();
        headers.set("X-Forwarded-For", "198.18." + (n / 250) + "." + (n % 250 + 1));
        return headers;
    }

    private ResponseEntity<String> get(String token) {
        return restTemplate.exchange(BASE + token, HttpMethod.GET, new HttpEntity<>(headers()), String.class);
    }

    private ResponseEntity<String> submit(String token, String itemsJson) {
        return restTemplate.exchange(BASE + token + "/reviews", HttpMethod.POST,
                new HttpEntity<>("{\"turnstileToken\":\"t\",\"items\":" + itemsJson + "}", headers()), String.class);
    }

    /** One item as JSON — serialised by Jackson so a comment's quotes/backslashes can never corrupt the body. */
    private static String item(UUID bookingId, int rating, String comment) {
        Map<String, Object> item = new java.util.LinkedHashMap<>();
        item.put("bookingId", bookingId.toString());
        item.put("rating", rating);
        if (comment != null) {
            item.put("comment", comment);
        }
        try {
            return ITEM_JSON.writeValueAsString(item);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A {@code SENT} invite for {@code subjectColumn} = {@code subjectId} whose raw token is {@code token}. */
    private UUID sentInvite(String subjectColumn, UUID subjectId, String token, Instant sentAt, Instant expiresAt) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO review_invites (id, " + subjectColumn + ", phone_hash, token_hash, status, "
                        + "send_after, sent_at, expires_at, created_at) VALUES (?, ?, ?, ?, 'SENT', ?, ?, ?, ?)",
                id, subjectId, phoneHasher.hash(GUEST_PHONE).hash(), ReviewInviteTokenHasher.hash(token),
                Timestamp.from(sentAt), Timestamp.from(sentAt), Timestamp.from(expiresAt), Timestamp.from(sentAt));
        return id;
    }

    private UUID sentVisitInvite(UUID appointmentId, String token) {
        Instant now = Instant.now();
        return sentInvite("appointment_id", appointmentId, token, now.minus(Duration.ofHours(1)),
                now.plus(Duration.ofDays(14)));
    }

    private UUID sentBookingInvite(UUID bookingId, String token) {
        Instant now = Instant.now();
        return sentInvite("booking_id", bookingId, token, now.minus(Duration.ofHours(1)),
                now.plus(Duration.ofDays(14)));
    }

    private JsonNode json(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody());
    }

    private List<Map<String, Object>> reviewsOf(UUID bookingId) {
        return jdbcTemplate.queryForList("SELECT * FROM reviews WHERE booking_id = ?", bookingId);
    }

    private int reviewReceivedNotifications() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM in_app_notification WHERE recipient_user_id = ? "
                + "AND type = 'REVIEW_RECEIVED'", Integer.class, masterUserId);
    }

    private LocalDate kyivDateOf(UUID bookingId) {
        return jdbcTemplate.queryForObject("SELECT starts_at FROM bookings WHERE id = ?", Timestamp.class, bookingId)
                .toInstant().atZone(ZoneId.of("Europe/Kyiv")).toLocalDate();
    }

    /** The 404 body minus its per-request correlation id. */
    private JsonNode withoutRequestId(ResponseEntity<String> response) throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode node =
                (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(response.getBody());
        node.remove("requestId");
        return node;
    }

    private void assertTokenNeverLogged(String token) {
        assertThat(logLines()).noneMatch(line -> line.contains(token));
    }

    // ── visit-keyed ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("visit invite: GET lists the visit's services by start time with «Ім'я П.»; POST of a subset → 201, "
            + "GUEST_LINK rows, rating recalculated, provider notified; then GET = ALREADY_REVIEWED, POST = 404")
    void should_submitOnceAndRecalculate_when_visitInviteRedeemed() throws Exception {
        UUID appointmentId = fixtures.linkVisit("COMPLETED", GUEST_PHONE, POLICY);
        UUID earlier = fixtures.visitItem(appointmentId, masterId, masterServiceId, "LINK", "COMPLETED", GUEST_PHONE, 6);
        UUID later = fixtures.visitItem(appointmentId, masterId, masterServiceId, "LINK", "COMPLETED", GUEST_PHONE, 4);
        String token = newToken();
        UUID inviteId = sentVisitInvite(appointmentId, token);

        ResponseEntity<String> read = get(token);
        ResponseEntity<String> created = submit(token, "[" + item(later, 4, "Дуже добре") + "]");
        ResponseEntity<String> readAfter = get(token);
        ResponseEntity<String> again = submit(token, "[" + item(earlier, 5, null) + "]");

        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode form = json(read);
        assertThat(form.path("state").asText()).isEqualTo("OPEN");
        assertThat(form.path("providerType").asText()).isEqualTo("MASTER");
        assertThat(form.path("providerName").asText()).isEqualTo("Ірина Коваль");
        assertThat(form.path("displayName").asText()).isEqualTo("Марія Ш.");
        assertThat(form.path("visitDate").asText()).isEqualTo(kyivDateOf(earlier).toString());
        assertThat(form.path("items")).hasSize(2);
        assertThat(form.path("items").get(0).path("bookingId").asText()).isEqualTo(earlier.toString());
        assertThat(form.path("items").get(1).path("bookingId").asText()).isEqualTo(later.toString());
        assertThat(form.path("items").get(0).path("masterName").asText()).isEqualTo("Ірина Коваль");
        assertThat(read.getBody()).doesNotContain(GUEST_PHONE).doesNotContain(GUEST_SUBSCRIBER_DIGITS)
                .doesNotContain("Шевченко");

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(json(created).path("created").asInt()).isEqualTo(1);
        List<Map<String, Object>> rows = reviewsOf(later);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("source")).isEqualTo("GUEST_LINK");
        assertThat(rows.get(0).get("client_id")).isNull();
        assertThat(rows.get(0).get("guest_display_name")).isEqualTo("Марія Ш.");
        assertThat(rows.get(0).get("comment")).isEqualTo("Дуже добре");
        assertThat(reviewsOf(earlier)).isEmpty();
        assertThat(inviteRow(inviteId).get("status")).isEqualTo("CONSUMED");
        assertThat(inviteRow(inviteId).get("consumed_at")).isNotNull();
        assertThat(jdbcTemplate.queryForObject("SELECT review_count FROM masters WHERE id = ?", Integer.class,
                masterId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT avg_rating FROM masters WHERE id = ?", BigDecimal.class,
                masterId)).isEqualByComparingTo("4");
        assertThat(reviewReceivedNotifications()).isEqualTo(1);

        assertThat(readAfter.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(readAfter).path("state").asText()).isEqualTo("ALREADY_REVIEWED");
        assertThat(json(readAfter).has("items")).isFalse();
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(reviewsOf(earlier)).isEmpty();
        assertTokenNeverLogged(token);
    }

    @Test
    @DisplayName("D5a: a STAFF walk-in item or an APP booking id is not in the invite → 409, the token stays SENT")
    void should_return409AndKeepToken_when_bookingNotInVisit() {
        UUID appointmentId = completedLinkVisit(GUEST_PHONE, POLICY);
        UUID foreignLink = fixtures.singleLinkBooking(masterId, masterServiceId, "COMPLETED", GUEST_PHONE, POLICY, 3);
        UUID clientId = fixtures.user("CLIENT", "Анна", "Бойко");
        UUID appBooking = fixtures.singleClientBooking(clientId, masterId, masterServiceId, "APP", "COMPLETED", 8);
        String token = newToken();
        UUID inviteId = sentVisitInvite(appointmentId, token);

        ResponseEntity<String> foreign = submit(token, "[" + item(foreignLink, 5, null) + "]");
        ResponseEntity<String> app = submit(token, "[" + item(appBooking, 5, null) + "]");

        assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(app.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(inviteRow(inviteId).get("status")).isEqualTo("SENT");
        assertThat(reviewsOf(foreignLink)).isEmpty();
        assertThat(reviewsOf(appBooking)).isEmpty();
    }

    @Test
    @DisplayName("an already-reviewed item → 409 and the whole submit rolls back (token stays SENT, no other row)")
    void should_rollBackConsume_when_anItemAlreadyReviewed() {
        UUID appointmentId = fixtures.linkVisit("COMPLETED", GUEST_PHONE, POLICY);
        UUID first = fixtures.visitItem(appointmentId, masterId, masterServiceId, "LINK", "COMPLETED", GUEST_PHONE, 6);
        UUID second = fixtures.visitItem(appointmentId, masterId, masterServiceId, "LINK", "COMPLETED", GUEST_PHONE, 4);
        fixtures.review(second, null, masterId, "GUEST_LINK", "Марія Ш.");
        String token = newToken();
        UUID inviteId = sentVisitInvite(appointmentId, token);

        ResponseEntity<String> response = submit(token, "[" + item(first, 5, null) + "," + item(second, 3, null) + "]");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(inviteRow(inviteId).get("status")).isEqualTo("SENT");
        assertThat(reviewsOf(first)).isEmpty();
    }

    // ── booking-keyed (D8) ─────────────────────────────────────────────────────

    @Test
    @DisplayName("booking invite: GET has exactly its one booking; another booking id → 409; its booking → 201 "
            + "GUEST_LINK + recalculated + notified; a second POST → 404")
    void should_acceptOnlyItsBooking_when_bookingInviteRedeemed() throws Exception {
        UUID bookingId = fixtures.singleLinkBooking(masterId, masterServiceId, "COMPLETED", GUEST_PHONE, POLICY, 5);
        UUID other = fixtures.singleLinkBooking(masterId, masterServiceId, "COMPLETED", GUEST_PHONE, POLICY, 8);
        String token = newToken();
        UUID inviteId = sentBookingInvite(bookingId, token);

        ResponseEntity<String> read = get(token);
        ResponseEntity<String> wrong = submit(token, "[" + item(other, 5, null) + "]");
        ResponseEntity<String> created = submit(token, "[" + item(bookingId, 5, "Супер") + "]");
        ResponseEntity<String> again = submit(token, "[" + item(bookingId, 5, null) + "]");

        JsonNode form = json(read);
        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(form.path("items")).hasSize(1);
        assertThat(form.path("items").get(0).path("bookingId").asText()).isEqualTo(bookingId.toString());
        assertThat(form.path("items").get(0).path("serviceName").asText()).isNotBlank();
        assertThat(form.path("items").get(0).path("masterName").asText()).isEqualTo("Ірина Коваль");
        assertThat(form.path("visitDate").asText()).isEqualTo(kyivDateOf(bookingId).toString());
        assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(reviewsOf(bookingId)).singleElement()
                .satisfies(row -> assertThat(row.get("source")).isEqualTo("GUEST_LINK"));
        assertThat(jdbcTemplate.queryForObject("SELECT review_count FROM masters WHERE id = ?", Integer.class,
                masterId)).isEqualTo(1);
        assertThat(reviewReceivedNotifications()).isEqualTo(1);
        assertThat(inviteRow(inviteId).get("status")).isEqualTo("CONSUMED");
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertTokenNeverLogged(token);
    }

    @Test
    @DisplayName("booking invite whose booking is no longer a reviewable LINK booking (gained a visit header) → 404")
    void should_return404_when_bookingInviteBookingIneligible() {
        UUID bookingId = fixtures.singleLinkBooking(masterId, masterServiceId, "COMPLETED", GUEST_PHONE, POLICY, 5);
        UUID appointmentId = fixtures.linkVisit("COMPLETED", GUEST_PHONE, POLICY);
        jdbcTemplate.update("UPDATE bookings SET appointment_id = ? WHERE id = ?", appointmentId, bookingId);
        String token = newToken();
        sentBookingInvite(bookingId, token);

        ResponseEntity<String> read = get(token);
        ResponseEntity<String> post = submit(token, "[" + item(bookingId, 5, null) + "]");

        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(post.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── provider card (website MasterStrip) ────────────────────────────────────

    @Test
    @DisplayName("provider card: no reviews → reviewCount 0, no avgRating, avatar + title present; after a review "
            + "lands → avgRating + reviewCount from the master's recalculated counters")
    void should_returnProviderCardRating_when_masterWithAndWithoutReviews() throws Exception {
        UUID masterUser = fixtures.masterUserId(masterId);
        jdbcTemplate.update("UPDATE users SET avatar_url = ?, professional_title = ? WHERE id = ?",
                "https://cdn.beautica.test/u/avatar.jpg", "Майстер манікюру", masterUser);
        UUID reviewed = fixtures.singleLinkBooking(masterId, masterServiceId, "COMPLETED", GUEST_PHONE, POLICY, 9);
        UUID pending = fixtures.singleLinkBooking(masterId, masterServiceId, "COMPLETED", GUEST_PHONE, POLICY, 5);
        String firstToken = newToken();
        String secondToken = newToken();
        sentBookingInvite(reviewed, firstToken);
        sentBookingInvite(pending, secondToken);

        ResponseEntity<String> beforeAnyReview = get(secondToken);
        ResponseEntity<String> created = submit(firstToken, "[" + item(reviewed, 4, null) + "]");
        ResponseEntity<String> afterReview = get(secondToken);

        assertThat(beforeAnyReview.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode none = json(beforeAnyReview);
        assertThat(none.path("reviewCount").isInt()).isTrue();
        assertThat(none.path("reviewCount").asInt()).isZero();
        assertThat(none.has("avgRating")).isFalse();
        assertThat(none.path("avatarUrl").asText()).isEqualTo("https://cdn.beautica.test/u/avatar.jpg");
        assertThat(none.path("professionalTitle").asText()).isEqualTo("Майстер манікюру");
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(afterReview.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode rated = json(afterReview);
        assertThat(rated.path("reviewCount").asInt()).isEqualTo(1);
        assertThat(rated.path("avgRating").decimalValue()).isEqualByComparingTo("4");
        assertThat(rated.path("avatarUrl").asText()).isEqualTo("https://cdn.beautica.test/u/avatar.jpg");
        assertThat(rated.path("professionalTitle").asText()).isEqualTo("Майстер манікюру");
        assertThat(afterReview.getBody()).doesNotContain(masterId.toString()).doesNotContain(masterUser.toString());
    }

    @Test
    @DisplayName("provider card: a deactivated master (hidden from its booking link) → form still opens, card fields "
            + "empty (no avatar/title/rating, reviewCount 0)")
    void should_hideProviderCard_when_masterDeactivated() throws Exception {
        jdbcTemplate.update("UPDATE users SET avatar_url = ?, professional_title = ? WHERE id = ?",
                "https://cdn.beautica.test/u/avatar.jpg", "Майстер манікюру", fixtures.masterUserId(masterId));
        jdbcTemplate.update("UPDATE masters SET review_count = 3, avg_rating = 4.67, is_active = false WHERE id = ?",
                masterId);
        UUID bookingId = fixtures.singleLinkBooking(masterId, masterServiceId, "COMPLETED", GUEST_PHONE, POLICY, 5);
        String token = newToken();
        sentBookingInvite(bookingId, token);

        ResponseEntity<String> read = get(token);

        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode form = json(read);
        assertThat(form.path("state").asText()).isEqualTo("OPEN");
        assertThat(form.path("providerName").asText()).isEqualTo("Ірина Коваль");
        assertThat(form.path("reviewCount").asInt()).isZero();
        assertThat(form.has("avgRating")).isFalse();
        assertThat(form.has("avatarUrl")).isFalse();
        assertThat(form.has("professionalTitle")).isFalse();
    }

    // ── audit-fix cycle 1: notifications stay per booking (architect D10) ──────

    private List<String> reviewReceivedKeys(UUID recipientUserId) {
        return jdbcTemplate.queryForList("SELECT dedup_key FROM in_app_notification WHERE recipient_user_id = ? "
                + "AND type = 'REVIEW_RECEIVED'", String.class, recipientUserId);
    }

    @Test
    @DisplayName("D10: a 2-item submit → exactly 2 REVIEW_RECEIVED notifications for the master, one per booking")
    void should_notifyMasterOncePerBooking_when_twoItemsSubmitted() {
        UUID appointmentId = fixtures.linkVisit("COMPLETED", GUEST_PHONE, POLICY);
        UUID first = fixtures.visitItem(appointmentId, masterId, masterServiceId, "LINK", "COMPLETED", GUEST_PHONE, 6);
        UUID second = fixtures.visitItem(appointmentId, masterId, masterServiceId, "LINK", "COMPLETED", GUEST_PHONE, 4);
        String token = newToken();
        sentVisitInvite(appointmentId, token);

        ResponseEntity<String> created = submit(token, "[" + item(first, 5, null) + "," + item(second, 4, null) + "]");

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(reviewReceivedKeys(masterUserId))
                .containsExactlyInAnyOrder("REVIEW_RECEIVED:" + first, "REVIEW_RECEIVED:" + second);
    }

    @Test
    @DisplayName("D10: a 2-item salon submit → 2 REVIEW_RECEIVED for the master AND 2 for the owner (owner ≠ master)")
    void should_notifyMasterAndOwnerOncePerBooking_when_twoSalonItemsSubmitted() {
        UUID salonId = affiliateMasterWithSalon();
        UUID appointmentId = fixtures.linkVisit("COMPLETED", GUEST_PHONE, POLICY);
        UUID first = fixtures.visitItem(appointmentId, masterId, masterServiceId, "LINK", "COMPLETED", GUEST_PHONE, 6);
        UUID second = fixtures.visitItem(appointmentId, masterId, masterServiceId, "LINK", "COMPLETED", GUEST_PHONE, 4);
        attachToSalon(salonId, first, second);
        String token = newToken();
        sentVisitInvite(appointmentId, token);

        ResponseEntity<String> created = submit(token, "[" + item(first, 5, null) + "," + item(second, 4, null) + "]");

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        List<String> expected = List.of("REVIEW_RECEIVED:" + first, "REVIEW_RECEIVED:" + second);
        assertThat(reviewReceivedKeys(masterUserId)).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(reviewReceivedKeys(salonOwnerId(salonId))).containsExactlyInAnyOrderElementsOf(expected);
    }

    // ── audit-fix cycle 1: statement counts of the GET ─────────────────────────

    @Test
    @DisplayName("GET of a master card whose master belongs to a salon costs 2 statements (invite + booking graph): "
            + "master.salon is fetched with the graph, never lazily")
    void should_readOpenFormInTwoStatements_when_masterBelongsToSalon() {
        affiliateMasterWithSalon();
        UUID bookingId = fixtures.singleLinkBooking(masterId, masterServiceId, "COMPLETED", GUEST_PHONE, POLICY, 5);
        String token = newToken();
        sentBookingInvite(bookingId, token);
        Statistics statistics = HibernateStatistics.enabledOn(entityManagerFactory);
        statistics.clear();

        ReviewInviteResponse response = guestReviewService.getInvite(token);
        long statements = statistics.getPrepareStatementCount();

        assertThat(response.state()).isEqualTo(ReviewInviteResponse.STATE_OPEN);
        assertThat(response.providerType()).isEqualTo(ReviewInviteResponse.PROVIDER_MASTER);
        assertThat(statements).isEqualTo(2);
    }

    @Test
    @DisplayName("GET of a consumed or unknown token costs exactly 1 statement (one invite lookup)")
    void should_answerInOneStatement_when_tokenConsumedOrUnknown() {
        UUID bookingId = fixtures.singleLinkBooking(masterId, masterServiceId, "COMPLETED", GUEST_PHONE, POLICY, 5);
        String token = newToken();
        UUID inviteId = sentBookingInvite(bookingId, token);
        jdbcTemplate.update("UPDATE review_invites SET status = 'CONSUMED', consumed_at = NOW() WHERE id = ?", inviteId);
        Statistics statistics = HibernateStatistics.enabledOn(entityManagerFactory);

        statistics.clear();
        ReviewInviteResponse consumed = guestReviewService.getInvite(token);
        long consumedStatements = statistics.getPrepareStatementCount();
        statistics.clear();
        Throwable unknown = org.assertj.core.api.Assertions.catchThrowable(() -> guestReviewService.getInvite(newToken()));
        long unknownStatements = statistics.getPrepareStatementCount();

        assertThat(consumed).isEqualTo(ReviewInviteResponse.alreadyReviewed());
        assertThat(consumedStatements).isEqualTo(1);
        assertThat(unknown).isInstanceOf(com.beautica.common.exception.NotFoundException.class);
        assertThat(unknownStatements).isEqualTo(1);
    }

    // ── D2 identical 404 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("expired (SENT past expires_at), EXPIRED, unknown and malformed tokens → one identical 404 body")
    void should_returnIdentical404_when_tokenExpiredUnknownOrMalformed() throws Exception {
        UUID appointmentId = completedLinkVisit(GUEST_PHONE, POLICY);
        UUID itemId = jdbcTemplate.queryForObject("SELECT id FROM bookings WHERE appointment_id = ?", UUID.class,
                appointmentId);
        String expiredToken = newToken();
        Instant now = Instant.now();
        sentInvite("appointment_id", appointmentId, expiredToken, now.minus(Duration.ofDays(15)),
                now.minus(Duration.ofMinutes(1)));

        ResponseEntity<String> expired = get(expiredToken);
        ResponseEntity<String> expiredPost = submit(expiredToken, "[" + item(itemId, 5, null) + "]");
        ResponseEntity<String> unknown = get(newToken());
        ResponseEntity<String> malformed = get("not!a~token");

        assertThat(List.of(expired, expiredPost, unknown, malformed))
                .allSatisfy(r -> assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThat(withoutRequestId(expiredPost)).isEqualTo(withoutRequestId(unknown));
        assertThat(withoutRequestId(expired)).isEqualTo(withoutRequestId(unknown));
        assertThat(withoutRequestId(malformed)).isEqualTo(withoutRequestId(unknown));
        assertThat(reviewsOf(itemId)).isEmpty();
    }

    // ── D4 concurrency ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("two concurrent submits of one token → exactly one 201, the other 404, exactly one review row")
    void should_letExactlyOneWin_when_twoSubmitsRace() throws Exception {
        UUID bookingId = fixtures.singleLinkBooking(masterId, masterServiceId, "COMPLETED", GUEST_PHONE, POLICY, 5);
        String token = newToken();
        sentBookingInvite(bookingId, token);
        CountDownLatch start = new CountDownLatch(1);
        Callable<HttpStatus> attempt = () -> {
            start.await();
            return HttpStatus.valueOf(submit(token, "[" + item(bookingId, 5, null) + "]").getStatusCode().value());
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<HttpStatus> statuses = new ArrayList<>();
        try {
            List<Future<HttpStatus>> futures = List.of(pool.submit(attempt), pool.submit(attempt));
            start.countDown();
            for (Future<HttpStatus> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(statuses).containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.NOT_FOUND);
        assertThat(reviewsOf(bookingId)).hasSize(1);
    }
}
