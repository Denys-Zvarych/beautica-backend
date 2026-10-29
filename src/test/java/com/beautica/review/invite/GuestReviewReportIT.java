package com.beautica.review.invite;

import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.Role;
import com.beautica.common.security.PhoneHasher;
import com.beautica.review.invite.service.ReviewInviteTokenHasher;
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

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 401 D7 — a review written through the guest link is an ordinary {@code REVIEW} report target (phase 395) and
 * is auto-hidden by the phase-396 rule (three eligible clients) exactly like an app review. The review is created
 * through the real public submit, not inserted. Same {@code @TestPropertySource} as {@code ReviewInviteSchedulerIT}.
 */
@TestPropertySource(properties = {
        "app.booking.sms.enabled=true",
        "app.booking.review-invite.enabled=true",
        "app.booking.review-invite.min-policy-version=2026-10-20"
})
@DisplayName("Guest review reportable — phase 401 IT")
class GuestReviewReportIT extends AbstractReviewInviteIT {

    private static final String TOKEN = "ReportableGuestToken01";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private PhoneHasher phoneHasher;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private static HttpHeaders jsonHeaders(String ip) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", ip);
        return headers;
    }

    private UUID submitGuestReview(UUID bookingId) {
        Instant sentAt = Instant.now().minus(Duration.ofHours(1));
        jdbcTemplate.update("INSERT INTO review_invites (id, booking_id, phone_hash, token_hash, status, send_after, "
                        + "sent_at, expires_at, created_at) VALUES (?, ?, ?, ?, 'SENT', ?, ?, ?, ?)",
                UUID.randomUUID(), bookingId, phoneHasher.hash(GUEST_PHONE).hash(), ReviewInviteTokenHasher.hash(TOKEN),
                Timestamp.from(sentAt), Timestamp.from(sentAt), Timestamp.from(sentAt.plus(Duration.ofDays(14))),
                Timestamp.from(sentAt));
        ResponseEntity<String> created = restTemplate.exchange("/api/v1/public/review-invites/" + TOKEN + "/reviews",
                HttpMethod.POST, new HttpEntity<>("{\"items\":[{\"bookingId\":\"" + bookingId
                        + "\",\"rating\":1,\"comment\":\"Погано\"}]}", jsonHeaders("198.21.0.1")), String.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return jdbcTemplate.queryForObject("SELECT id FROM reviews WHERE booking_id = ?", UUID.class, bookingId);
    }

    private ResponseEntity<String> report(UUID reporterId, UUID reviewId) {
        HttpHeaders headers = jsonHeaders("198.21.1." + (Math.abs(reporterId.hashCode()) % 250 + 1));
        String email = jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, reporterId);
        headers.setBearerAuth(jwtTokenProvider.generateAccessToken(reporterId, email, Role.CLIENT));
        String body = "{\"targetType\":\"REVIEW\",\"targetId\":\"" + reviewId + "\",\"reason\":\"OFFENSIVE\"}";
        return restTemplate.exchange("/api/v1/reports", HttpMethod.POST, new HttpEntity<>(body, headers),
                String.class);
    }

    private String moderationStatus(UUID reviewId) {
        return jdbcTemplate.queryForObject("SELECT moderation_status FROM reviews WHERE id = ?", String.class,
                reviewId);
    }

    @Test
    @DisplayName("three eligible clients report a GUEST_LINK review → 201 each, VISIBLE after two, HIDDEN_PENDING "
            + "after three")
    void should_autoHideGuestLinkReview_when_threeEligibleClientsReport() {
        List<UUID> reporters = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            UUID clientId = fixtures.user("CLIENT", "Клієнт", "Тест");
            fixtures.singleClientBooking(clientId, masterId, masterServiceId, "APP", "COMPLETED", 10 + 2 * i);
            reporters.add(clientId);
        }
        UUID bookingId = fixtures.singleLinkBooking(masterId, masterServiceId, "COMPLETED", GUEST_PHONE, POLICY, 5);
        UUID reviewId = submitGuestReview(bookingId);

        ResponseEntity<String> first = report(reporters.get(0), reviewId);
        ResponseEntity<String> second = report(reporters.get(1), reviewId);
        String afterTwo = moderationStatus(reviewId);
        ResponseEntity<String> third = report(reporters.get(2), reviewId);

        assertThat(List.of(first, second, third))
                .allSatisfy(r -> assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED));
        assertThat(jdbcTemplate.queryForObject("SELECT source FROM reviews WHERE id = ?", String.class, reviewId))
                .isEqualTo("GUEST_LINK");
        assertThat(afterTwo).isEqualTo("VISIBLE");
        assertThat(moderationStatus(reviewId)).isEqualTo("HIDDEN_PENDING");
    }
}
