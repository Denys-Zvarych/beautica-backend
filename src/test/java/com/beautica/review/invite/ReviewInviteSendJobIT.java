package com.beautica.review.invite;

import com.beautica.common.security.PhoneHasher;
import com.beautica.review.invite.entity.SmsOptOut;
import com.beautica.review.invite.entity.SmsOptOutSource;
import com.beautica.review.invite.repository.ReviewInviteRepository;
import com.beautica.review.invite.repository.SmsOptOutRepository;
import com.beautica.review.invite.service.PreparedInviteSms;
import com.beautica.review.invite.service.ReviewInviteSendJob;
import com.beautica.review.invite.service.ReviewInviteSendService;
import com.beautica.review.invite.service.ReviewInviteTokenHasher;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 400 D3/D4/D6 — {@code ReviewInviteSendJob} against real Postgres and the real gated {@code SmsService}
 * (Turbosms → WireMock), both flags on. Every tick is driven at a pinned instant ({@code tick(now)}); the timer is
 * parked by the test profile.
 */
@TestPropertySource(properties = {
        "app.booking.sms.enabled=true",
        "app.booking.review-invite.enabled=true",
        "app.booking.review-invite.min-policy-version=2026-10-20",
        // Audit cycle 1: short read cap so the ambiguous-outcome test waits ~1 s, not the 5 s default.
        "app.sms.turbosms.read-timeout=1s"
})
@DisplayName("ReviewInviteSendJob — phase 400 IT")
class ReviewInviteSendJobIT extends AbstractReviewInviteIT {

    @Autowired
    private ReviewInviteSendJob job;

    @Autowired
    private ReviewInviteRepository reviewInviteRepository;

    @Autowired
    private SmsOptOutRepository smsOptOutRepository;

    @Autowired
    private PhoneHasher phoneHasher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ReviewInviteSendService sendService;

    @Test
    @DisplayName("a due invite is sent once: exact text, SENT, SHA-256(token) stored, 14-day expiry, raw token "
            + "nowhere in the row or the logs, phone never logged")
    void should_sendOnceAndStoreOnlyTokenHash_when_inviteDue() throws Exception {
        UUID inviteId = dueInvite();

        job.tick(MIDDAY);

        List<LoggedRequest> requests = turbosmsRequests();
        assertThat(requests).hasSize(1);
        String text = payload(requests.get(0)).path("sms").path("text").asText();
        assertThat(payload(requests.get(0)).path("recipients").get(0).asText()).isEqualTo(GUEST_PHONE);
        assertThat(text).startsWith("Оцініть ваш візит: beautica.app/r/").hasSize(56);
        String token = text.substring(text.lastIndexOf('/') + 1);
        assertThat(token).matches("[A-Za-z0-9_-]{22}");

        Map<String, Object> row = inviteRow(inviteId);
        assertThat(row.get("status")).isEqualTo("SENT");
        assertThat((byte[]) row.get("token_hash")).isEqualTo(ReviewInviteTokenHasher.hash(token));
        assertThat(((Timestamp) row.get("sent_at")).toInstant()).isEqualTo(MIDDAY);
        assertThat(((Timestamp) row.get("expires_at")).toInstant()).isEqualTo(MIDDAY.plus(Duration.ofDays(14)));
        assertThat(row.values()).allSatisfy(value -> assertThat(String.valueOf(value)).doesNotContain(token));
        assertThat(logLines()).isNotEmpty()
                .noneMatch(line -> line.contains(token) || line.contains(GUEST_SUBSCRIBER_DIGITS));
        assertThat(logLines()).anyMatch(line -> line.contains("Review invite SMS sent: invite=" + inviteId));
    }

    @Test
    @DisplayName("two concurrent ticks over the same due row send exactly ONE SMS")
    void should_sendOnce_when_twoTicksRunConcurrently() throws Exception {
        UUID inviteId = dueInvite();
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        try {
            Future<?> a = pool.submit(() -> awaitThenTick(start));
            Future<?> b = pool.submit(() -> awaitThenTick(start));
            a.get(30, TimeUnit.SECONDS);
            b.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(turbosmsRequests()).hasSize(1);
        assertThat(inviteRow(inviteId).get("status")).isEqualTo("SENT");
    }

    @Test
    @DisplayName("SKIP LOCKED: a row locked by another claimer is skipped without blocking, then sent once")
    void should_skipLockedRowWithoutBlocking_when_anotherClaimerHoldsIt() throws Exception {
        UUID inviteId = dueInvite();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            assertThat(reviewInviteRepository.claimDue(MIDDAY, 50)).hasSize(1);
            locked.countDown();
            awaitQuietly(release);
            status.setRollbackOnly();
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        CompletableFuture.runAsync(() -> job.tick(MIDDAY)).get(10, TimeUnit.SECONDS);
        int sentWhileLocked = turbosmsRequests().size();
        release.countDown();
        holder.get(10, TimeUnit.SECONDS);
        job.tick(MIDDAY);

        assertThat(sentWhileLocked).isZero();
        assertThat(turbosmsRequests()).hasSize(1);
        assertThat(inviteRow(inviteId).get("status")).isEqualTo("SENT");
    }

    @Test
    @DisplayName("provider failure: token voided, retried next tick, FAILED after the 3rd attempt (3 calls, no more)")
    void should_failAfterThreeAttempts_when_providerKeepsFailing() {
        stubTurbosms(500, "{\"response_status\":\"ERROR\"}");
        UUID inviteId = dueInvite();

        job.tick(MIDDAY);
        Map<String, Object> afterFirst = inviteRow(inviteId);
        job.tick(MIDDAY.plusSeconds(60));
        job.tick(MIDDAY.plusSeconds(120));
        job.tick(MIDDAY.plusSeconds(180));

        assertThat(afterFirst.get("status")).isEqualTo("SCHEDULED");
        assertThat(afterFirst.get("token_hash")).isNull();
        assertThat(((Number) afterFirst.get("attempts")).intValue()).isEqualTo(1);
        Map<String, Object> row = inviteRow(inviteId);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(((Number) row.get("attempts")).intValue()).isEqualTo(3);
        assertThat(row.get("token_hash")).isNull();
        assertThat(row.get("sent_at")).isNull();
        assertThat(turbosmsRequests()).hasSize(3);
    }

    @Test
    @DisplayName("audit cycle 1 — provider read timeout (outcome unknown): FAILED at once, exactly ONE request, never "
            + "retried on later ticks, token voided")
    void should_markFailedWithoutRetry_when_providerReadTimesOut() {
        TURBOSMS.stubFor(post(urlEqualTo(TURBOSMS_PATH))
                .willReturn(aResponse().withStatus(200).withFixedDelay(3_000)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"response_status\":\"OK\"}")));
        UUID inviteId = dueInvite();

        job.tick(MIDDAY);
        job.tick(MIDDAY.plusSeconds(60));
        job.tick(MIDDAY.plusSeconds(120));

        assertThat(turbosmsRequests()).hasSize(1);
        Map<String, Object> row = inviteRow(inviteId);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(((Number) row.get("attempts")).intValue()).isEqualTo(1);
        assertThat(row.get("token_hash")).isNull();
        assertThat(row.get("sent_at")).isNull();
    }

    @Test
    @DisplayName("audit cycle 1 — a claimed row released unsent (budget out) is SCHEDULED again with no token and no "
            + "attempt counted, then goes out once on the next tick")
    void should_requeueAndSendNextTick_when_claimedRowReleasedUnsent() {
        UUID inviteId = dueInvite();
        List<PreparedInviteSms> claimed = sendService.claimAndPrepare(MIDDAY);

        sendService.releaseUnsent(claimed.stream().map(PreparedInviteSms::inviteId).toList());
        Map<String, Object> released = inviteRow(inviteId);
        job.tick(MIDDAY.plusSeconds(60));

        assertThat(claimed).hasSize(1);
        assertThat(released.get("status")).isEqualTo("SCHEDULED");
        assertThat(released.get("token_hash")).isNull();
        assertThat(released.get("sent_at")).isNull();
        assertThat(released.get("expires_at")).isNull();
        assertThat(((Number) released.get("attempts")).intValue()).isZero();
        assertThat(turbosmsRequests()).hasSize(1);
        assertThat(inviteRow(inviteId).get("status")).isEqualTo("SENT");
    }

    @Test
    @DisplayName("audit cycle 1 — batch opt-out preload: of two due invites only the opted-out phone is skipped")
    void should_skipOnlyOptedOutPhone_when_batchPreloadsOptOuts() {
        String otherPhone = "+380503334455";
        UUID optedOutInvite = dueInvite();
        UUID otherAppointment = completedLinkVisit(otherPhone, POLICY);
        UUID otherInvite = scheduledInvite(otherAppointment, phoneHasher.hash(otherPhone).hash(),
                MIDDAY.minusSeconds(60));
        smsOptOutRepository.saveAndFlush(
                SmsOptOut.of(phoneHasher.hash(GUEST_PHONE), SmsOptOutSource.REVIEW_INVITE, MIDDAY));

        job.tick(MIDDAY);

        assertThat(inviteRow(optedOutInvite).get("status")).isEqualTo("SKIPPED");
        assertThat(inviteRow(optedOutInvite).get("skip_reason")).isEqualTo("OPTED_OUT");
        assertThat(inviteRow(otherInvite).get("status")).isEqualTo("SENT");
        assertThat(turbosmsRequests()).hasSize(1);
    }

    @Test
    @DisplayName("unsent more than 72 h after send_after → SKIPPED (STALE), no SMS")
    void should_skipStale_when_overdueBeyond72h() {
        UUID appointmentId = completedLinkVisit(GUEST_PHONE, POLICY);
        UUID inviteId = scheduledInvite(appointmentId, phoneHash(),
                MIDDAY.minus(Duration.ofHours(72)).minusSeconds(1));

        job.tick(MIDDAY);

        assertSkipped(inviteId, "STALE");
    }

    @Test
    @DisplayName("inside the Kyiv quiet window nothing is sent; the row waits and goes out in the morning")
    void should_waitForMorning_when_tickInQuietHours() {
        UUID inviteId = dueInvite();

        job.tick(NIGHT);
        Object statusAtNight = inviteRow(inviteId).get("status");
        int callsAtNight = turbosmsRequests().size();
        job.tick(NIGHT.plus(Duration.ofHours(14))); // 13:00 Kyiv next day

        assertThat(statusAtNight).isEqualTo("SCHEDULED");
        assertThat(callsAtNight).isZero();
        assertThat(inviteRow(inviteId).get("status")).isEqualTo("SENT");
        assertThat(turbosmsRequests()).hasSize(1);
    }

    @Test
    @DisplayName("a phone that opted out after the invite was scheduled → SKIPPED (OPTED_OUT) at send, no SMS")
    void should_skipOptedOut_when_phoneOptedOutBeforeSend() {
        UUID inviteId = dueInvite();
        smsOptOutRepository.saveAndFlush(
                SmsOptOut.of(phoneHasher.hash(GUEST_PHONE), SmsOptOutSource.REVIEW_INVITE, MIDDAY));

        job.tick(MIDDAY);

        assertSkipped(inviteId, "OPTED_OUT");
    }

    @Test
    @DisplayName("every completed booking reviewed before the send → SKIPPED (NOTHING_TO_REVIEW), no SMS")
    void should_skipNothingToReview_when_visitAlreadyReviewed() {
        UUID appointmentId = fixtures.linkVisit("COMPLETED", GUEST_PHONE, POLICY);
        UUID bookingId = fixtures.visitItem(appointmentId, masterId, masterServiceId, "LINK", "COMPLETED",
                GUEST_PHONE, 5);
        fixtures.review(bookingId, null, masterId, "GUEST_LINK", "Марія Ш.");
        UUID inviteId = scheduledInvite(appointmentId, phoneHash(), MIDDAY.minusSeconds(60));

        job.tick(MIDDAY);

        assertSkipped(inviteId, "NOTHING_TO_REVIEW");
    }

    @Test
    @DisplayName("expiry sweep: SENT past expires_at → EXPIRED; SENT not yet expired untouched")
    void should_expireOnlyPastDue_when_sweeping() {
        UUID expired = sentInvite(MIDDAY.minusSeconds(1));
        UUID live = sentInvite(MIDDAY.plus(Duration.ofDays(1)));

        job.tick(NIGHT); // the sweep runs in quiet hours too

        assertThat(inviteRow(expired).get("status")).isEqualTo("EXPIRED");
        assertThat(inviteRow(live).get("status")).isEqualTo("SENT");
    }

    // ── Phase 400 D7 — booking-keyed invites ─────────────────────────────────

    @Test
    @DisplayName("D7 — a booking-keyed due invite is sent once with the same text and token handling")
    void should_sendOnce_when_bookingKeyedInviteDue() throws Exception {
        UUID inviteId = dueBookingInvite();

        job.tick(MIDDAY);
        job.tick(MIDDAY.plusSeconds(60));

        List<LoggedRequest> requests = turbosmsRequests();
        assertThat(requests).hasSize(1);
        String text = payload(requests.get(0)).path("sms").path("text").asText();
        assertThat(payload(requests.get(0)).path("recipients").get(0).asText()).isEqualTo(GUEST_PHONE);
        assertThat(text).startsWith("Оцініть ваш візит: beautica.app/r/").hasSize(56);
        Map<String, Object> row = inviteRow(inviteId);
        assertThat(row.get("status")).isEqualTo("SENT");
        assertThat((byte[]) row.get("token_hash"))
                .isEqualTo(ReviewInviteTokenHasher.hash(text.substring(text.lastIndexOf('/') + 1)));
    }

    @Test
    @DisplayName("D7 — one batch holding a visit-keyed and a booking-keyed invite sends both")
    void should_sendBoth_when_batchMixesSubjects() {
        UUID visitInvite = dueInvite();
        UUID bookingInvite = dueBookingInvite();

        job.tick(MIDDAY);

        assertThat(turbosmsRequests()).hasSize(2);
        assertThat(inviteRow(visitInvite).get("status")).isEqualTo("SENT");
        assertThat(inviteRow(bookingInvite).get("status")).isEqualTo("SENT");
    }

    @Test
    @DisplayName("D7 — the booking was reviewed before the send → SKIPPED (NOTHING_TO_REVIEW), no SMS")
    void should_skipNothingToReview_when_bookingAlreadyReviewed() {
        UUID bookingId = fixtures.singleLinkBooking(masterId, masterServiceId, "COMPLETED", GUEST_PHONE, POLICY, 5);
        fixtures.review(bookingId, null, masterId, "GUEST_LINK", "Марія Ш.");
        UUID inviteId = scheduledBookingInvite(bookingId, phoneHash(), MIDDAY.minusSeconds(60));

        job.tick(MIDDAY);

        assertSkipped(inviteId, "NOTHING_TO_REVIEW");
    }

    @Test
    @DisplayName("D7 — the booking was anonymised before the send → SKIPPED (ANONYMISED), no SMS")
    void should_skipAnonymised_when_bookingAnonymisedBeforeSend() {
        UUID bookingId = fixtures.singleLinkBooking(masterId, masterServiceId, "COMPLETED", GUEST_PHONE, POLICY, 5);
        UUID inviteId = scheduledBookingInvite(bookingId, phoneHash(), MIDDAY.minusSeconds(60));
        fixtures.anonymiseBooking(bookingId);

        job.tick(MIDDAY);

        assertSkipped(inviteId, "ANONYMISED");
    }

    private UUID dueBookingInvite() {
        UUID bookingId = fixtures.singleLinkBooking(masterId, masterServiceId, "COMPLETED", GUEST_PHONE, POLICY, 5);
        return scheduledBookingInvite(bookingId, phoneHash(), MIDDAY.minusSeconds(60));
    }

    private UUID dueInvite() {
        UUID appointmentId = completedLinkVisit(GUEST_PHONE, POLICY);
        return scheduledInvite(appointmentId, phoneHash(), MIDDAY.minusSeconds(60));
    }

    private UUID sentInvite(Instant expiresAt) {
        UUID appointmentId = completedLinkVisit(GUEST_PHONE, POLICY);
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO review_invites (id, appointment_id, phone_hash, token_hash, status, send_after, "
                        + "sent_at, expires_at, created_at) VALUES (?, ?, ?, ?, 'SENT', ?, ?, ?, ?)",
                id, appointmentId, phoneHash(), ReviewInviteTokenHasher.hash(UUID.randomUUID().toString()),
                Timestamp.from(expiresAt.minus(Duration.ofDays(14))), Timestamp.from(expiresAt.minus(Duration.ofDays(14))),
                Timestamp.from(expiresAt), Timestamp.from(expiresAt.minus(Duration.ofDays(14))));
        return id;
    }

    private byte[] phoneHash() {
        return phoneHasher.hash(GUEST_PHONE).hash();
    }

    private void assertSkipped(UUID inviteId, String reason) {
        Map<String, Object> row = inviteRow(inviteId);
        assertThat(row.get("status")).isEqualTo("SKIPPED");
        assertThat(row.get("skip_reason")).isEqualTo(reason);
        assertThat(row.get("token_hash")).isNull();
        assertThat(turbosmsRequests()).isEmpty();
    }

    private void awaitThenTick(CyclicBarrier start) {
        try {
            start.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        job.tick(MIDDAY);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
