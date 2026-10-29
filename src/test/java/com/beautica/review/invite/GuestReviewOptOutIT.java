package com.beautica.review.invite;

import com.beautica.booking.service.AppointmentTransitionService;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 401 D6 — {@code POST /api/v1/public/review-invites/{token}/opt-out}: any SENT / CONSUMED / EXPIRED token sent
 * within 30 days opts the phone out (idempotent); the next completed visit of that phone is then SKIPPED (OPTED_OUT)
 * by the phase-400 scheduler. Same {@code @TestPropertySource} as {@code ReviewInviteSchedulerIT} (shared context).
 */
@TestPropertySource(properties = {
        "app.booking.sms.enabled=true",
        "app.booking.review-invite.enabled=true",
        "app.booking.review-invite.min-policy-version=2026-10-20"
})
@DisplayName("Guest review opt-out — phase 401 IT")
class GuestReviewOptOutIT extends AbstractReviewInviteIT {

    private static final AtomicInteger IP_SEQ = new AtomicInteger();

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private PhoneHasher phoneHasher;

    @Autowired
    private AppointmentTransitionService appointmentTransitionService;

    private ResponseEntity<String> optOut(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        int n = IP_SEQ.incrementAndGet();
        headers.set("X-Forwarded-For", "198.19." + (n / 250) + "." + (n % 250 + 1));
        return restTemplate.exchange("/api/v1/public/review-invites/" + token + "/opt-out", HttpMethod.POST,
                new HttpEntity<>("{}", headers), String.class);
    }

    private void invite(String subjectColumn, UUID subjectId, String token, String status, Instant sentAt) {
        jdbcTemplate.update("INSERT INTO review_invites (id, " + subjectColumn + ", phone_hash, token_hash, status, "
                        + "send_after, sent_at, expires_at, consumed_at, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), subjectId, phoneHasher.hash(GUEST_PHONE).hash(), ReviewInviteTokenHasher.hash(token),
                status, Timestamp.from(sentAt), Timestamp.from(sentAt), Timestamp.from(sentAt.plus(Duration.ofDays(14))),
                "CONSUMED".equals(status) ? Timestamp.from(sentAt.plus(Duration.ofHours(1))) : null,
                Timestamp.from(sentAt));
    }

    private boolean optedOut() {
        return jdbcTemplate.queryForObject("SELECT EXISTS (SELECT 1 FROM sms_opt_outs WHERE phone_hash = ?)",
                Boolean.class, (Object) phoneHasher.hash(GUEST_PHONE).hash());
    }

    @Test
    @DisplayName("opt-out with a CONSUMED token → 204 (twice, idempotent); the next completed visit of that phone → "
            + "invite SKIPPED (OPTED_OUT)")
    void should_skipNextInvite_when_guestOptedOutAfterReviewing() {
        UUID reviewedVisit = completedLinkVisit(GUEST_PHONE, POLICY);
        String token = "OptOutConsumedToken_01";
        invite("appointment_id", reviewedVisit, token, "CONSUMED", Instant.now().minus(Duration.ofDays(2)));

        ResponseEntity<String> first = optOut(token);
        ResponseEntity<String> second = optOut(token);
        UUID nextVisit = fixtures.linkVisit("CONFIRMED", GUEST_PHONE, POLICY);
        fixtures.visitItem(nextVisit, masterId, masterServiceId, "LINK", "CONFIRMED", GUEST_PHONE, 3);
        authenticateAsMaster();
        appointmentTransitionService.completeAppointment(masterUserId, nextVisit);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM sms_opt_outs", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT source FROM sms_opt_outs", String.class))
                .isEqualTo("REVIEW_INVITE");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM review_invites WHERE appointment_id = ?",
                String.class, nextVisit)).isEqualTo("SKIPPED");
        assertThat(jdbcTemplate.queryForObject("SELECT skip_reason FROM review_invites WHERE appointment_id = ?",
                String.class, nextVisit)).isEqualTo("OPTED_OUT");
        assertThat(logLines()).noneMatch(line -> line.contains(token));
    }

    @Test
    @DisplayName("D8 — a booking-keyed EXPIRED token sent 29 days ago still opts out → 204")
    void should_optOut_when_bookingKeyedExpiredTokenWithinThirtyDays() {
        UUID bookingId = fixtures.singleLinkBooking(masterId, masterServiceId, "COMPLETED", GUEST_PHONE, POLICY, 5);
        String token = "OptOutExpiredBooking_1";
        invite("booking_id", bookingId, token, "EXPIRED", Instant.now().minus(Duration.ofDays(29)));

        ResponseEntity<String> response = optOut(token);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(optedOut()).isTrue();
    }

    @Test
    @DisplayName("a token sent 31 days ago, or an unknown token → 404 and nothing stored")
    void should_return404_when_tokenTooOldOrUnknown() {
        UUID bookingId = fixtures.singleLinkBooking(masterId, masterServiceId, "COMPLETED", GUEST_PHONE, POLICY, 5);
        String token = "OptOutTooOldToken_0001";
        invite("booking_id", bookingId, token, "EXPIRED", Instant.now().minus(Duration.ofDays(31)));

        ResponseEntity<String> tooOld = optOut(token);
        ResponseEntity<String> unknown = optOut("OptOutUnknownToken_001");

        assertThat(tooOld.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(optedOut()).isFalse();
    }
}
