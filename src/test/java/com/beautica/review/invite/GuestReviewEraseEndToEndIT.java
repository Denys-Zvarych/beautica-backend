package com.beautica.review.invite;

import com.beautica.booking.retention.GuestPii;
import com.beautica.common.security.PhoneHasher;
import com.beautica.review.invite.service.ReviewInviteTokenHasher;
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
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 402 — the whole guest-review erase path over HTTP against real Postgres, with the real caches and the real
 * {@code AFTER_COMMIT} listener (no service call shortcuts):
 * <ol>
 *   <li>a guest of a salon master redeems an SMS invite and leaves a review ({@code POST /public/review-invites/…});</li>
 *   <li>the public master / salon / detail views are read (and so cached) with the guest's «Ім'я П.»;</li>
 *   <li>support calls {@code POST /internal/guest-pii/erase} ({@code X-Internal-Key}) for that phone;</li>
 *   <li>the same public views now serve «Гість» (cache evicted after commit), rating, text and the rating summary
 *       are unchanged, the phone's {@code SENT} invite is {@code EXPIRED}, its {@code SCHEDULED} invite is
 *       {@code SKIPPED (ANONYMISED)}, its {@code sms_opt_outs} row is kept, and another phone's review and invite are
 *       untouched.</li>
 * </ol>
 *
 * <p>Same {@code @TestPropertySource} as {@code GuestReviewSubmitIT}, so the Spring context is shared.
 */
@TestPropertySource(properties = {
        "app.booking.sms.enabled=true",
        "app.booking.review-invite.enabled=true",
        "app.booking.review-invite.min-policy-version=2026-10-20"
})
@DisplayName("Guest review erase end to end over HTTP — phase 402 IT")
class GuestReviewEraseEndToEndIT extends AbstractReviewInviteIT {

    private static final String ERASE_URL = "/api/v1/internal/guest-pii/erase";
    private static final String INTERNAL_KEY = "test-internal-api-key";
    private static final String OTHER_PHONE = "+380675556677";
    private static final String GUEST_LABEL = "Марія Ш.";
    private static final String COMMENT = "Чудовий манікюр, дякую!";
    private static final AtomicInteger IP_SEQ = new AtomicInteger();

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private PhoneHasher phoneHasher;

    // ── HTTP helpers ───────────────────────────────────────────────────────────

    /** Each request its own client IP, so the per-IP public buckets never interfere. */
    private static HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        int n = IP_SEQ.incrementAndGet();
        headers.set("X-Forwarded-For", "198.17." + (n / 250) + "." + (n % 250 + 1));
        return headers;
    }

    private String json(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private ResponseEntity<String> submitReview(String token, UUID bookingId, int rating, String comment) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("bookingId", bookingId.toString());
        item.put("rating", rating);
        item.put("comment", comment);
        String body = json(Map.of("turnstileToken", "t", "items", List.of(item)));
        return restTemplate.exchange("/api/v1/public/review-invites/" + token + "/reviews", HttpMethod.POST,
                new HttpEntity<>(body, headers()), String.class);
    }

    private ResponseEntity<String> erase(String phone) {
        HttpHeaders headers = headers();
        headers.set("X-Internal-Key", INTERNAL_KEY);
        return restTemplate.exchange(ERASE_URL, HttpMethod.POST,
                new HttpEntity<>(json(Map.of("phone", phone, "ticketRef", "SUP-402")), headers), String.class);
    }

    private JsonNode getJson(String path) throws Exception {
        ResponseEntity<String> response = restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers()),
                String.class);
        assertThat(response.getStatusCode()).as("GET %s", path).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody()).path("data");
    }

    /** {@code [displayName, rating, comment]} of the review with {@code reviewId} in a public page (the PageResponse body nests the rows under its own {@code data}). */
    private static List<Object> row(JsonNode page, UUID reviewId) {
        for (JsonNode node : page.path("data")) {
            if (node.path("id").asText().equals(reviewId.toString())) {
                return List.of(node.path("clientDisplayName").asText(), node.path("rating").asInt(),
                        node.path("comment").asText());
            }
        }
        throw new AssertionError("review " + reviewId + " not in page " + page);
    }

    // ── seed helpers ───────────────────────────────────────────────────────────

    private UUID sentInvite(String subjectColumn, UUID subjectId, String phone, String token) {
        UUID id = UUID.randomUUID();
        Instant sentAt = Instant.now().minus(Duration.ofHours(1));
        jdbcTemplate.update("INSERT INTO review_invites (id, " + subjectColumn + ", phone_hash, token_hash, status, "
                        + "send_after, sent_at, expires_at, created_at) VALUES (?, ?, ?, ?, 'SENT', ?, ?, ?, ?)",
                id, subjectId, phoneHasher.hash(phone).hash(), ReviewInviteTokenHasher.hash(token),
                Timestamp.from(sentAt), Timestamp.from(sentAt), Timestamp.from(sentAt.plus(Duration.ofDays(14))),
                Timestamp.from(sentAt));
        return id;
    }

    private UUID reviewIdOf(UUID bookingId) {
        return jdbcTemplate.queryForObject("SELECT id FROM reviews WHERE booking_id = ?", UUID.class, bookingId);
    }

    private BigDecimal masterAvgRating() {
        return jdbcTemplate.queryForObject("SELECT avg_rating FROM masters WHERE id = ?", BigDecimal.class, masterId);
    }

    @Test
    @DisplayName("invite review → public views cached with «Марія Ш.» → internal erase → views serve «Гість», "
            + "rating/text/summary unchanged, SENT → EXPIRED, SCHEDULED → SKIPPED (ANONYMISED), opt-out kept, "
            + "another phone untouched")
    void should_anonymiseEverywhereAndKeepRating_when_guestReviewedThenPhoneErasedOverHttp() throws Exception {
        UUID salonId = affiliateMasterWithSalon();
        // 1. the guest's reviewed visit (salon-performed) — redeemed over HTTP below
        UUID reviewedVisit = fixtures.linkVisit("COMPLETED", GUEST_PHONE, POLICY);
        UUID reviewedItem = fixtures.visitItem(reviewedVisit, masterId, masterServiceId, "LINK", "COMPLETED",
                GUEST_PHONE, 30);
        attachToSalon(salonId, reviewedItem);
        String reviewToken = "E2eEraseReviewToken_01";
        UUID consumedInvite = sentInvite("appointment_id", reviewedVisit, GUEST_PHONE, reviewToken);
        // 2. the same phone's single booking with a delivered, not yet redeemed link
        UUID sentBooking = fixtures.singleLinkBooking(masterId, masterServiceId, "COMPLETED", GUEST_PHONE, POLICY, 20);
        attachToSalon(salonId, sentBooking);
        String sentToken = "E2eEraseSentToken_0001";
        UUID sentInvite = sentInvite("booking_id", sentBooking, GUEST_PHONE, sentToken);
        // 3. the same phone's visit whose invite is still waiting for its send time
        UUID pendingVisit = fixtures.linkVisit("COMPLETED", GUEST_PHONE, POLICY);
        UUID pendingItem = fixtures.visitItem(pendingVisit, masterId, masterServiceId, "LINK", "COMPLETED",
                GUEST_PHONE, 10);
        attachToSalon(salonId, pendingItem);
        UUID scheduledInvite = scheduledInvite(pendingVisit, phoneHasher.hash(GUEST_PHONE).hash(),
                Instant.now().plus(Duration.ofHours(2)));
        // 4. another guest of the same master: review + SCHEDULED invite
        UUID otherBooking = fixtures.singleLinkBooking(masterId, masterServiceId, "COMPLETED", OTHER_PHONE, POLICY, 40);
        attachToSalon(salonId, otherBooking);
        String otherToken = "E2eEraseOtherToken_001";
        sentInvite("booking_id", otherBooking, OTHER_PHONE, otherToken);
        // 5. the guest opted out earlier
        byte[] guestHash = phoneHasher.hash(GUEST_PHONE).hash();
        jdbcTemplate.update("INSERT INTO sms_opt_outs (phone_hash, source) VALUES (?, 'REVIEW_INVITE')", guestHash);

        assertThat(submitReview(reviewToken, reviewedItem, 5, COMMENT).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(submitReview(otherToken, otherBooking, 3, "Нормально").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        UUID review = reviewIdOf(reviewedItem);
        UUID otherReview = reviewIdOf(otherBooking);
        String masterPage = "/api/v1/masters/" + masterId + "/reviews";
        String salonPage = "/api/v1/salons/" + salonId + "/reviews";
        String detail = "/api/v1/reviews/" + review;
        String summary = "/api/v1/masters/" + masterId + "/reviews/summary";
        // warm every cached public view with the guest's label
        assertThat(row(getJson(masterPage), review)).containsExactly(GUEST_LABEL, 5, COMMENT);
        assertThat(row(getJson(salonPage), review)).containsExactly(GUEST_LABEL, 5, COMMENT);
        assertThat(getJson(detail).path("clientDisplayName").asText()).isEqualTo(GUEST_LABEL);
        JsonNode summaryBefore = getJson(summary);
        BigDecimal avgBefore = masterAvgRating();

        ResponseEntity<String> erased = erase("0502223344");

        assertThat(erased.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(row(getJson(masterPage), review)).as("master page, cache evicted")
                .containsExactly(GuestPii.ANONYMISED_NAME, 5, COMMENT);
        assertThat(row(getJson(salonPage), review)).as("salon page, cache evicted")
                .containsExactly(GuestPii.ANONYMISED_NAME, 5, COMMENT);
        assertThat(getJson(detail).path("clientDisplayName").asText()).as("review detail, cache evicted")
                .isEqualTo(GuestPii.ANONYMISED_NAME);
        assertThat(row(getJson(masterPage), otherReview)).as("another phone's review keeps its label")
                .containsExactly(GUEST_LABEL, 3, "Нормально");
        assertThat(getJson(summary)).as("rating summary unchanged").isEqualTo(summaryBefore);
        assertThat(masterAvgRating()).as("masters.avg_rating unchanged").isEqualByComparingTo(avgBefore);

        assertThat(inviteRow(consumedInvite)).as("redeemed invite stays CONSUMED").containsEntry("status", "CONSUMED");
        assertThat(inviteRow(sentInvite)).as("delivered link dies").containsEntry("status", "EXPIRED");
        assertThat(inviteRow(scheduledInvite)).as("pending invite never sends")
                .containsEntry("status", "SKIPPED").containsEntry("skip_reason", "ANONYMISED");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM review_invites WHERE booking_id = ?", String.class,
                otherBooking)).as("another phone's invite").isEqualTo("CONSUMED");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM sms_opt_outs WHERE phone_hash = ?",
                Integer.class, (Object) guestHash)).as("402 D2: the opt-out survives the erase").isEqualTo(1);
        assertThat(restTemplate.exchange("/api/v1/public/review-invites/" + sentToken, HttpMethod.GET,
                new HttpEntity<>(headers()), String.class).getStatusCode()).as("expired link → 404")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("internal erase without X-Internal-Key → 401, nothing anonymised, invites untouched")
    void should_rejectAndChangeNothing_when_eraseCalledWithoutInternalKey() throws Exception {
        UUID booking = fixtures.singleLinkBooking(masterId, masterServiceId, "COMPLETED", GUEST_PHONE, POLICY, 20);
        String token = "E2eEraseNoKeyToken_001";
        UUID invite = sentInvite("booking_id", booking, GUEST_PHONE, token);
        UUID bookingForReview = fixtures.singleLinkBooking(masterId, masterServiceId, "COMPLETED", GUEST_PHONE,
                POLICY, 40);
        UUID review = fixtures.review(bookingForReview, null, masterId, "GUEST_LINK", GUEST_LABEL);

        ResponseEntity<String> response = restTemplate.exchange(ERASE_URL, HttpMethod.POST,
                new HttpEntity<>(json(Map.of("phone", GUEST_PHONE)), headers()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(jdbcTemplate.queryForObject("SELECT guest_display_name FROM reviews WHERE id = ?", String.class,
                review)).isEqualTo(GUEST_LABEL);
        assertThat(inviteRow(invite)).containsEntry("status", "SENT");
    }
}
