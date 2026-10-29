package com.beautica.review.invite;

import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.Role;
import com.beautica.booking.event.GuestBookingCompletedEvent;
import com.beautica.common.security.PhoneHasher;
import com.beautica.config.BookingSmsProperties;
import com.beautica.review.invite.config.ReviewInviteProperties;
import com.beautica.review.invite.entity.SmsOptOut;
import com.beautica.review.invite.entity.SmsOptOutSource;
import com.beautica.review.invite.repository.ReviewInviteRepository;
import com.beautica.review.invite.repository.SmsOptOutRepository;
import com.beautica.review.invite.service.QuietHoursCalculator;
import com.beautica.review.invite.service.ReviewInviteEligibility;
import com.beautica.review.invite.service.ReviewInviteScheduler;
import com.beautica.review.invite.service.ReviewInviteSendJob;
import com.beautica.review.invite.service.ReviewInviteTokenHasher;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 400 QA — the whole invite chain as production runs it: a provider completes over REST → the AFTER_COMMIT
 * consumer writes the row → a send-job tick → the SMS reaches Turbosms (WireMock) → the row is {@code SENT} holding
 * only SHA-256(token). Also the gates that can flip between create and send (anonymisation, opt-out), and the D3
 * quiet-hours boundary with a fixed {@link Clock} in both Kyiv offsets.
 *
 * <p>Same {@code @TestPropertySource} as {@code ReviewInviteSchedulerIT}, so the Spring context is shared.
 */
@TestPropertySource(properties = {
        "app.booking.sms.enabled=true",
        "app.booking.review-invite.enabled=true",
        "app.booking.review-invite.min-policy-version=2026-10-20"
})
@DisplayName("Review invite — phase 400 end-to-end IT (REST completion → send job → SMS)")
class ReviewInviteEndToEndIT extends AbstractReviewInviteIT {

    private static final String REVIEW_LINK_PREFIX = "Оцініть ваш візит: beautica.app/r/";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private ReviewInviteSendJob job;

    @Autowired
    private ReviewInviteRepository reviewInviteRepository;

    @Autowired
    private ReviewInviteEligibility eligibility;

    @Autowired
    private QuietHoursCalculator quietHours;

    @Autowired
    private ReviewInviteProperties reviewInviteProperties;

    @Autowired
    private BookingSmsProperties bookingSmsProperties;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private SmsOptOutRepository smsOptOutRepository;

    @Autowired
    private PhoneHasher phoneHasher;

    // ── REST completion → row → tick → SMS ─────────────────────────────────────

    @Test
    @DisplayName("PATCH /bookings/{id}/complete on a single LINK booking → booking-keyed row after commit → one "
            + "tick sends ONE SMS with a beautica.app/r/ link → row SENT holding only SHA-256(token)")
    void should_sendOneReviewLinkSms_when_providerCompletesSingleLinkBookingOverRest() throws Exception {
        UUID bookingId = fixtures.singleLinkBooking(masterId, masterServiceId, "CONFIRMED", GUEST_PHONE, POLICY, 2);

        ResponseEntity<String> completed = patch("/api/v1/bookings/" + bookingId + "/complete");
        Map<String, Object> created = jdbcTemplate.queryForMap(
                "SELECT * FROM review_invites WHERE booking_id = ?", bookingId);
        int smsBeforeTick = turbosmsRequests().size();
        Instant sendAfter = ((Timestamp) created.get("send_after")).toInstant();
        job.tick(sendAfter);
        job.tick(sendAfter.plusSeconds(60));

        assertThat(completed.getStatusCode()).as(completed.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(created.get("status")).isEqualTo("SCHEDULED");
        assertThat(created.get("appointment_id")).isNull();
        assertThat(smsBeforeTick).as("completion itself never sends").isZero();
        String token = assertOneReviewSmsTo(GUEST_PHONE);
        assertSentWithHashOnly((UUID) created.get("id"), token, sendAfter);
    }

    @Test
    @DisplayName("2-service LINK visit completed item by item over REST → ONE visit-keyed row, two ticks → exactly "
            + "ONE SMS, row SENT with hash only")
    void should_sendExactlyOneSms_when_providerCompletesTwoServiceLinkVisitItemByItemOverRest() throws Exception {
        UUID appointmentId = fixtures.linkVisit("CONFIRMED", GUEST_PHONE, POLICY);
        UUID first = fixtures.visitItem(appointmentId, masterId, masterServiceId, "LINK", "CONFIRMED", GUEST_PHONE, 3);
        UUID second = fixtures.visitItem(appointmentId, masterId, masterServiceId, "LINK", "CONFIRMED", GUEST_PHONE,
                2);

        ResponseEntity<String> firstDone = patch(
                "/api/v1/appointments/" + appointmentId + "/services/" + first + "/complete");
        ResponseEntity<String> secondDone = patch(
                "/api/v1/appointments/" + appointmentId + "/services/" + second + "/complete");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM review_invites");
        Instant sendAfter = ((Timestamp) rows.get(0).get("send_after")).toInstant();
        job.tick(sendAfter);
        job.tick(sendAfter.plusSeconds(60));

        assertThat(firstDone.getStatusCode()).as(firstDone.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(secondDone.getStatusCode()).as(secondDone.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM appointments WHERE id = ?", String.class,
                appointmentId)).as("header collapsed after the last item").isEqualTo("COMPLETED");
        assertThat(rows).as("two item completions, one invite per visit").hasSize(1);
        assertThat(rows.get(0).get("appointment_id")).isEqualTo(appointmentId);
        assertThat(rows.get(0).get("booking_id")).isNull();
        String token = assertOneReviewSmsTo(GUEST_PHONE);
        assertSentWithHashOnly((UUID) rows.get(0).get("id"), token, sendAfter);
    }

    @Test
    @DisplayName("STAFF walk-in visit (guest phone on file) completed over REST → no invite row, a tick sends nothing")
    void should_createNoRowAndSendNothing_when_staffWalkInVisitCompletedOverRest() {
        UUID appointmentId = fixtures.staffGuestVisit("CONFIRMED", GUEST_PHONE);
        fixtures.visitItem(appointmentId, masterId, masterServiceId, "STAFF", "CONFIRMED", GUEST_PHONE, 3);

        ResponseEntity<String> completed = patch("/api/v1/appointments/" + appointmentId + "/complete");
        job.tick(MIDDAY.plus(Duration.ofDays(400)));

        assertThat(completed.getStatusCode()).as(completed.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM appointments WHERE id = ?", String.class,
                appointmentId)).isEqualTo("COMPLETED");
        assertThat(inviteCount()).isZero();
        assertThat(turbosmsRequests()).isEmpty();
    }

    // ── gates flipping between create and send ────────────────────────────────

    @Test
    @DisplayName("LINK visit completed over REST, then the retention job anonymises it before the send → "
            + "SKIPPED (ANONYMISED), no token, no SMS")
    void should_skipAnonymised_when_visitAnonymisedBetweenCreateAndSend() {
        UUID appointmentId = fixtures.linkVisit("CONFIRMED", GUEST_PHONE, POLICY);
        UUID item = fixtures.visitItem(appointmentId, masterId, masterServiceId, "LINK", "CONFIRMED", GUEST_PHONE, 3);
        patch("/api/v1/appointments/" + appointmentId + "/complete");
        UUID inviteId = singleInviteId();
        String statusAtCreate = (String) inviteRow(inviteId).get("status");

        anonymiseVisit(appointmentId, item);
        job.tick(sendAfterOf(inviteId));

        assertThat(statusAtCreate).isEqualTo("SCHEDULED");
        assertSkippedWithoutSms(inviteId, "ANONYMISED");
    }

    @Test
    @DisplayName("single LINK booking completed over REST, then the guest opts out before the send → "
            + "SKIPPED (OPTED_OUT), no token, no SMS")
    void should_skipOptedOut_when_phoneOptsOutBetweenCreateAndSend() {
        UUID bookingId = fixtures.singleLinkBooking(masterId, masterServiceId, "CONFIRMED", GUEST_PHONE, POLICY, 2);
        patch("/api/v1/bookings/" + bookingId + "/complete");
        UUID inviteId = singleInviteId();
        String statusAtCreate = (String) inviteRow(inviteId).get("status");

        smsOptOutRepository.saveAndFlush(
                SmsOptOut.of(phoneHasher.hash(GUEST_PHONE), SmsOptOutSource.REVIEW_INVITE, Instant.now()));
        job.tick(sendAfterOf(inviteId));

        assertThat(statusAtCreate).isEqualTo("SCHEDULED");
        assertSkippedWithoutSms(inviteId, "OPTED_OUT");
    }

    // ── D3 quiet-hours boundary, fixed Clock, both Kyiv offsets ───────────────

    @ParameterizedTest(name = "{0}: completed {1} → send_after {2}")
    @CsvSource({
            "summer UTC+3, 2026-06-10T15:59:00Z, 2026-06-10T17:59:00Z",
            "winter UTC+2, 2026-01-15T16:59:00Z, 2026-01-15T18:59:00Z"
    })
    @DisplayName("D3 — completed 18:59 Kyiv → send_after 20:59 the same evening (last minute before quiet), sent by "
            + "a tick at 20:59")
    void should_sendAt2059_when_completedAt1859Kyiv(String season, Instant completedAt, Instant expectedSendAfter)
            throws Exception {
        UUID bookingId = fixtures.singleLinkBooking(masterId, masterServiceId, "COMPLETED", GUEST_PHONE, POLICY, 5);

        fixedClockScheduler(completedAt).onGuestBookingCompleted(new GuestBookingCompletedEvent(bookingId, completedAt));
        UUID inviteId = singleInviteId();
        Map<String, Object> created = inviteRow(inviteId);
        job.tick(expectedSendAfter.minusSeconds(1));
        int sentBeforeDue = turbosmsRequests().size();
        job.tick(expectedSendAfter);

        assertThat(((Timestamp) created.get("send_after")).toInstant()).as(season).isEqualTo(expectedSendAfter);
        assertThat(((Timestamp) created.get("created_at")).toInstant()).as("row stamped by the fixed clock")
                .isEqualTo(completedAt);
        assertThat(sentBeforeDue).as("not due one second earlier").isZero();
        String token = assertOneReviewSmsTo(GUEST_PHONE);
        assertSentWithHashOnly(inviteId, token, expectedSendAfter);
    }

    @ParameterizedTest(name = "{0}: completed {1} → send_after {3}")
    @CsvSource({
            "summer UTC+3, 2026-06-10T16:00:00Z, 2026-06-10T18:00:00Z, 2026-06-11T07:00:00Z",
            "winter UTC+2, 2026-01-15T17:00:00Z, 2026-01-15T19:00:00Z, 2026-01-16T08:00:00Z"
    })
    @DisplayName("D3 — completed 19:00 Kyiv → candidate 21:00 is quiet → send_after 10:00 next day; ticks at 21:00 "
            + "and 09:59 send nothing, the 10:00 tick sends once")
    void should_deferToNext1000_when_completedAt1900Kyiv(String season, Instant completedAt, Instant quietStart,
                                                       Instant expectedSendAfter) throws Exception {
        UUID bookingId = fixtures.singleLinkBooking(masterId, masterServiceId, "COMPLETED", GUEST_PHONE, POLICY, 5);

        fixedClockScheduler(completedAt).onGuestBookingCompleted(new GuestBookingCompletedEvent(bookingId, completedAt));
        UUID inviteId = singleInviteId();
        Instant sendAfter = sendAfterOf(inviteId);
        job.tick(quietStart);
        job.tick(expectedSendAfter.minusSeconds(1));
        int sentBeforeTen = turbosmsRequests().size();
        job.tick(expectedSendAfter);

        assertThat(sendAfter).as(season).isEqualTo(expectedSendAfter);
        assertThat(sentBeforeTen).as("nothing at 21:00 nor at 09:59").isZero();
        String token = assertOneReviewSmsTo(GUEST_PHONE);
        assertSentWithHashOnly(inviteId, token, expectedSendAfter);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "summer UTC+3, 2026-06-10T17:59:59Z, 2026-06-10T18:00:00Z",
            "winter UTC+2, 2026-01-15T18:59:59Z, 2026-01-15T19:00:00Z"
    })
    @DisplayName("D3 at send time — a due row is sent by a tick at 20:59:59 Kyiv; an identical row is NOT sent by a "
            + "tick at exactly 21:00:00")
    void should_sendAt205959ButNotAt2100_when_rowDue(String season, Instant lastLoudSecond, Instant quietStart) {
        UUID loudBooking = fixtures.singleLinkBooking(masterId, masterServiceId, "COMPLETED", GUEST_PHONE, POLICY, 5);
        UUID loudInvite = scheduledBookingInvite(loudBooking, phoneHash(), lastLoudSecond.minus(Duration.ofHours(1)));
        job.tick(lastLoudSecond);
        int sentAtLastLoudSecond = turbosmsRequests().size();

        UUID quietBooking = fixtures.singleLinkBooking(masterId, masterServiceId, "COMPLETED", "+380503334455",
                POLICY, 6);
        UUID quietInvite = scheduledBookingInvite(quietBooking, phoneHasher.hash("+380503334455").hash(),
                quietStart.minus(Duration.ofHours(1)));
        job.tick(quietStart);

        assertThat(sentAtLastLoudSecond).as(season).isEqualTo(1);
        assertThat(inviteRow(loudInvite).get("status")).isEqualTo("SENT");
        assertThat(inviteRow(quietInvite).get("status")).as("21:00:00 is inside [21:00, 09:00)")
                .isEqualTo("SCHEDULED");
        assertThat(inviteRow(quietInvite).get("token_hash")).isNull();
        assertThat(turbosmsRequests()).hasSize(1);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ResponseEntity<String> patch(String path) {
        String email = jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, masterUserId);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtTokenProvider.generateAccessToken(masterUserId, email, Role.INDEPENDENT_MASTER));
        return restTemplate.exchange(path, HttpMethod.PATCH, new HttpEntity<>(headers), String.class);
    }

    /** A scheduler identical to the bean but stamped by a fixed clock — the D3 boundary without touching the context. */
    private ReviewInviteScheduler fixedClockScheduler(Instant now) {
        return new ReviewInviteScheduler(reviewInviteRepository, eligibility, quietHours, reviewInviteProperties,
                bookingSmsProperties, Clock.fixed(now, ZoneOffset.UTC), transactionManager);
    }

    /** The retention job's erasure of a guest visit: items first, then the header (AppointmentRepository shape). */
    private void anonymiseVisit(UUID appointmentId, UUID itemId) {
        fixtures.anonymiseBooking(itemId);
        jdbcTemplate.update("UPDATE appointments SET guest_surname = NULL, guest_phone = NULL, client_comment = NULL, "
                + "client_cancellation_note = NULL, cancel_token_hash = NULL, guest_pii_anonymised_at = NOW(), "
                + "updated_at = NOW() WHERE id = ?", appointmentId);
    }

    private UUID singleInviteId() {
        assertThat(inviteCount()).as("exactly one invite row").isEqualTo(1);
        return jdbcTemplate.queryForObject("SELECT id FROM review_invites", UUID.class);
    }

    private Instant sendAfterOf(UUID inviteId) {
        return ((Timestamp) inviteRow(inviteId).get("send_after")).toInstant();
    }

    private byte[] phoneHash() {
        return phoneHasher.hash(GUEST_PHONE).hash();
    }

    /** Exactly one Turbosms request, to {@code phone}, carrying the review link; returns the raw token. */
    private String assertOneReviewSmsTo(String phone) throws Exception {
        List<LoggedRequest> requests = turbosmsRequests();
        assertThat(requests).as("exactly one SMS").hasSize(1);
        assertThat(payload(requests.get(0)).path("recipients").get(0).asText()).isEqualTo(phone);
        String text = payload(requests.get(0)).path("sms").path("text").asText();
        assertThat(text).startsWith(REVIEW_LINK_PREFIX).hasSize(56);
        String token = text.substring(REVIEW_LINK_PREFIX.length());
        assertThat(token).matches("[A-Za-z0-9_-]{22}");
        return token;
    }

    private void assertSentWithHashOnly(UUID inviteId, String token, Instant sentAt) {
        Map<String, Object> row = inviteRow(inviteId);
        assertThat(row.get("status")).isEqualTo("SENT");
        assertThat((byte[]) row.get("token_hash")).isEqualTo(ReviewInviteTokenHasher.hash(token));
        assertThat(((Timestamp) row.get("sent_at")).toInstant()).isEqualTo(sentAt);
        assertThat(((Timestamp) row.get("expires_at")).toInstant()).isEqualTo(sentAt.plus(Duration.ofDays(14)));
        assertThat(row.values()).as("raw token in no column")
                .allSatisfy(value -> assertThat(String.valueOf(value)).doesNotContain(token));
        assertThat(logLines()).as("raw token and phone never logged")
                .noneMatch(line -> line.contains(token) || line.contains(GUEST_SUBSCRIBER_DIGITS));
    }

    private void assertSkippedWithoutSms(UUID inviteId, String reason) {
        Map<String, Object> row = inviteRow(inviteId);
        assertThat(row.get("status")).isEqualTo("SKIPPED");
        assertThat(row.get("skip_reason")).isEqualTo(reason);
        assertThat(row.get("token_hash")).isNull();
        assertThat(row.get("sent_at")).isNull();
        assertThat(turbosmsRequests()).isEmpty();
    }
}
