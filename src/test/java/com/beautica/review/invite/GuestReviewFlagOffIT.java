package com.beautica.review.invite;

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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 401 D2 — with {@code app.booking.review-invite.enabled=false} a perfectly valid SENT token is the same 404 on
 * every route (read, submit, opt-out) and nothing is written. Same {@code @TestPropertySource} as
 * {@code ReviewInviteInvitesDisabledIT} (shared context).
 */
@TestPropertySource(properties = "app.booking.sms.enabled=true")
@DisplayName("Guest review API, flag off — phase 401 IT")
class GuestReviewFlagOffIT extends AbstractReviewInviteIT {

    private static final String TOKEN = "FlagOffValidToken_0001";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private PhoneHasher phoneHasher;

    private ResponseEntity<String> call(HttpMethod method, String path, String body, String ip) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", ip);
        return restTemplate.exchange("/api/v1/public/review-invites/" + TOKEN + path, method,
                new HttpEntity<>(body, headers), String.class);
    }

    @Test
    @DisplayName("flag off → GET, POST /reviews and POST /opt-out are 404; no review, no opt-out, token stays SENT")
    void should_return404Everywhere_when_flagOff() {
        UUID bookingId = fixtures.singleLinkBooking(masterId, masterServiceId, "COMPLETED", GUEST_PHONE, POLICY, 5);
        Instant sentAt = Instant.now().minus(Duration.ofHours(1));
        UUID inviteId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO review_invites (id, booking_id, phone_hash, token_hash, status, send_after, "
                        + "sent_at, expires_at, created_at) VALUES (?, ?, ?, ?, 'SENT', ?, ?, ?, ?)",
                inviteId, bookingId, phoneHasher.hash(GUEST_PHONE).hash(), ReviewInviteTokenHasher.hash(TOKEN),
                Timestamp.from(sentAt), Timestamp.from(sentAt), Timestamp.from(sentAt.plus(Duration.ofDays(14))),
                Timestamp.from(sentAt));

        ResponseEntity<String> read = call(HttpMethod.GET, "", null, "198.20.0.1");
        ResponseEntity<String> submit = call(HttpMethod.POST, "/reviews",
                "{\"items\":[{\"bookingId\":\"" + bookingId + "\",\"rating\":5}]}", "198.20.0.2");
        ResponseEntity<String> optOut = call(HttpMethod.POST, "/opt-out", "{}", "198.20.0.3");

        assertThat(List.of(read, submit, optOut))
                .allSatisfy(r -> assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM reviews", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM sms_opt_outs", Integer.class)).isZero();
        assertThat(inviteRow(inviteId).get("status")).isEqualTo("SENT");
    }
}
