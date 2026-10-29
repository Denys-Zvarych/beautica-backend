package com.beautica.review.invite;

import com.beautica.booking.retention.GuestPiiAnonymisationService;
import com.beautica.review.invite.service.GuestReviewRetentionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 402 D3 — review invite retention against real Postgres: terminal invites are deleted 30 days after their
 * terminal moment (both subjects, {@code LIMIT}-bounded batches within the per-run budget; {@code SKIPPED} /
 * {@code FAILED} on {@code created_at}, a stalled {@code SENT} on {@code expires_at}), {@code SCHEDULED} rows never; and the {@code SCHEDULED} invite of an anonymised visit or single LINK booking becomes
 * {@code SKIPPED (ANONYMISED)} while every other row keeps its status.
 *
 * <p>Same {@code @TestPropertySource} as {@code GuestReviewSubmitIT}, so the Spring context is shared.
 */
@TestPropertySource(properties = {
        "app.booking.sms.enabled=true",
        "app.booking.review-invite.enabled=true",
        "app.booking.review-invite.min-policy-version=2026-10-20"
})
@DisplayName("Review invite purge + anonymisation skip — phase 402 IT")
class ReviewInvitePurgeIT extends AbstractReviewInviteIT {

    private static final String OTHER_PHONE = "+380675556677";
    private static final int BATCH = 500;
    private static final int BUDGET = 10;

    @Autowired private GuestReviewRetentionService retentionService;
    @Autowired private GuestPiiAnonymisationService anonymisationService;

    private Instant now;

    private UUID visit() {
        return fixtures.linkVisit("COMPLETED", GUEST_PHONE, POLICY);
    }

    private UUID singleBooking(int hoursAgo) {
        return fixtures.singleLinkBooking(masterId, masterServiceId, "COMPLETED", GUEST_PHONE, POLICY, hoursAgo);
    }

    private static byte[] hash32() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        return ByteBuffer.allocate(32).putLong(a.getMostSignificantBits()).putLong(a.getLeastSignificantBits())
                .putLong(b.getMostSignificantBits()).putLong(b.getLeastSignificantBits()).array();
    }

    private static Timestamp ago(Instant now, long days) {
        return Timestamp.from(now.minus(Duration.ofDays(days)));
    }

    /** An unsent invite ({@code SCHEDULED}, {@code SKIPPED} or {@code FAILED}) created and due {@code daysAgo} ago. */
    private UUID unsentInvite(String subjectColumn, UUID subjectId, String status, long daysAgo) {
        return unsentInvite(subjectColumn, subjectId, status, now.minus(Duration.ofDays(daysAgo)), Duration.ZERO);
    }

    /** An unsent invite created at {@code createdAt} whose {@code send_after} is {@code deferral} later. */
    private UUID unsentInvite(String subjectColumn, UUID subjectId, String status, Instant createdAt,
                              Duration deferral) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO review_invites (id, " + subjectColumn + ", phone_hash, status, send_after, "
                        + "skip_reason, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, subjectId, hash32(), status, Timestamp.from(createdAt.plus(deferral)),
                "SKIPPED".equals(status) ? "STALE" : null, Timestamp.from(createdAt));
        return id;
    }

    /** A sent invite in {@code status} ({@code SENT}, {@code CONSUMED} or {@code EXPIRED}). */
    private UUID sentInvite(String subjectColumn, UUID subjectId, String status, long sentDaysAgo,
                            long expiresDaysAgo, Long consumedDaysAgo) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO review_invites (id, " + subjectColumn + ", phone_hash, token_hash, status, "
                        + "send_after, sent_at, expires_at, consumed_at, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, subjectId, hash32(), hash32(), status, ago(now, sentDaysAgo), ago(now, sentDaysAgo),
                ago(now, expiresDaysAgo), consumedDaysAgo == null ? null : ago(now, consumedDaysAgo),
                ago(now, sentDaysAgo));
        return id;
    }

    private boolean exists(UUID inviteId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM review_invites WHERE id = ?)", Boolean.class, inviteId));
    }

    @Test
    @DisplayName("purge: terminal rows older than 30 days deleted (both subjects), incl. a stalled SENT past expiry and "
            + "a FAILED row whose send_after was deferred; recent, SCHEDULED, unexpired-window SENT kept")
    void should_deleteOnlyOldTerminalInvites_when_purging() {
        now = Instant.now();
        UUID oldConsumed = sentInvite("appointment_id", visit(), "CONSUMED", 40, 26, 31L);
        UUID recentConsumed = sentInvite("appointment_id", visit(), "CONSUMED", 35, 21, 29L);
        UUID oldExpired = sentInvite("appointment_id", visit(), "EXPIRED", 45, 31, null);
        UUID recentExpired = sentInvite("appointment_id", visit(), "EXPIRED", 40, 26, null);
        UUID oldSkipped = unsentInvite("appointment_id", visit(), "SKIPPED", 31);
        UUID recentSkipped = unsentInvite("appointment_id", visit(), "SKIPPED", 29);
        UUID oldFailed = unsentInvite("appointment_id", visit(), "FAILED", now.minus(Duration.ofDays(31)),
                Duration.ofDays(6));
        UUID oldScheduled = unsentInvite("appointment_id", visit(), "SCHEDULED", 60);
        UUID oldSent = sentInvite("appointment_id", visit(), "SENT", 60, 46, null);
        UUID recentStalledSent = sentInvite("appointment_id", visit(), "SENT", 43, 29, null);
        UUID oldBookingConsumed = sentInvite("booking_id", singleBooking(1000), "CONSUMED", 40, 26, 31L);
        UUID recentBookingExpired = sentInvite("booking_id", singleBooking(800), "EXPIRED", 30, 16, null);

        int purged = retentionService.purgeInvites(now, BATCH, BUDGET);

        assertThat(purged).isEqualTo(6);
        assertThat(exists(oldConsumed)).isFalse();
        assertThat(exists(oldExpired)).isFalse();
        assertThat(exists(oldSkipped)).isFalse();
        assertThat(exists(oldFailed)).isFalse();
        assertThat(exists(oldBookingConsumed)).isFalse();
        assertThat(exists(oldSent)).as("stalled SENT 46 days past expiry").isFalse();
        assertThat(exists(recentConsumed)).isTrue();
        assertThat(exists(recentExpired)).isTrue();
        assertThat(exists(recentSkipped)).isTrue();
        assertThat(exists(oldScheduled)).isTrue();
        assertThat(exists(recentStalledSent)).as("stalled SENT only 29 days past expiry").isTrue();
        assertThat(exists(recentBookingExpired)).isTrue();
    }

    @Test
    @DisplayName("audit-fix: a SKIPPED row is purged on its created_at clock even when send_after was deferred past it")
    void should_purgeSkippedAtCreate_when_sendAfterDeferredPastCreatedAt() {
        now = Instant.now();
        Instant createdAt = now.minus(Duration.ofDays(30)).minus(Duration.ofHours(1));
        UUID deferred = unsentInvite("appointment_id", visit(), "SKIPPED", createdAt, Duration.ofHours(15));
        UUID recentDeferred = unsentInvite("appointment_id", visit(), "SKIPPED",
                now.minus(Duration.ofDays(29)), Duration.ofHours(15));

        int purged = retentionService.purgeInvites(now, BATCH, BUDGET);

        assertThat(purged).isEqualTo(1);
        assertThat(exists(deferred)).as("created 30 d 1 h ago; send_after 29 d 10 h ago").isFalse();
        assertThat(exists(recentDeferred)).isTrue();
    }

    @Test
    @DisplayName("purge: LIMIT-bounded batches drain a backlog; the per-run budget leaves the rest for the next run")
    void should_purgeInBoundedBatches_when_backlogExceedsBatch() {
        now = Instant.now();
        for (int i = 0; i < 5; i++) {
            unsentInvite("appointment_id", visit(), "SKIPPED", 40);
        }

        int firstRun = retentionService.purgeInvites(now, 2, 2);
        int secondRun = retentionService.purgeInvites(now, 2, BUDGET);

        assertThat(firstRun).isEqualTo(4);
        assertThat(secondRun).isEqualTo(1);
        assertThat(inviteCount()).isZero();
    }

    @Test
    @DisplayName("retention anonymisation: SCHEDULED visit- and booking-keyed invites → SKIPPED (ANONYMISED); "
            + "a SENT invite and a not-yet-due booking's invite keep their status")
    void should_skipScheduledInvitesOfAnonymisedSubjects_when_retentionRuns() {
        now = Instant.now();
        UUID dueVisit = visit();
        fixtures.visitItem(dueVisit, masterId, masterServiceId, "LINK", "COMPLETED", GUEST_PHONE, 48);
        UUID dueSingle = singleBooking(52);
        UUID dueSentSingle = singleBooking(56);
        UUID recentSingle = fixtures.singleLinkBooking(masterId, masterServiceId, "COMPLETED", OTHER_PHONE, POLICY, 3);
        UUID visitInvite = unsentInvite("appointment_id", dueVisit, "SCHEDULED", 1);
        UUID bookingInvite = unsentInvite("booking_id", dueSingle, "SCHEDULED", 1);
        UUID sentInvite = sentInvite("booking_id", dueSentSingle, "SENT", 1, -13, null);
        UUID recentInvite = unsentInvite("booking_id", recentSingle, "SCHEDULED", 0);

        anonymisationService.anonymiseDue(now.minus(Duration.ofDays(1)));

        assertThat(inviteRow(visitInvite)).containsEntry("status", "SKIPPED").containsEntry("skip_reason", "ANONYMISED");
        assertThat(inviteRow(bookingInvite)).containsEntry("status", "SKIPPED")
                .containsEntry("skip_reason", "ANONYMISED");
        assertThat(inviteRow(sentInvite)).containsEntry("status", "SENT");
        assertThat(inviteRow(recentInvite)).containsEntry("status", "SCHEDULED");
    }

    @Test
    @DisplayName("erase: the phone's SCHEDULED booking-keyed invite → SKIPPED (ANONYMISED); another phone's is kept")
    void should_skipScheduledInviteOfErasedPhone_when_erasingByPhone() {
        now = Instant.now();
        UUID erased = singleBooking(3);
        UUID other = fixtures.singleLinkBooking(masterId, masterServiceId, "COMPLETED", OTHER_PHONE, POLICY, 6);
        UUID erasedInvite = unsentInvite("booking_id", erased, "SCHEDULED", 0);
        UUID otherInvite = unsentInvite("booking_id", other, "SCHEDULED", 0);

        anonymisationService.eraseByPhone(GUEST_PHONE, null);

        assertThat(inviteRow(erasedInvite)).containsEntry("status", "SKIPPED")
                .containsEntry("skip_reason", "ANONYMISED");
        assertThat(inviteRow(otherInvite)).containsEntry("status", "SCHEDULED");
    }
}
