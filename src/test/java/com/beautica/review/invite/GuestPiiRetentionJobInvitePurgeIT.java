package com.beautica.review.invite;

import com.beautica.booking.retention.GuestPii;
import com.beautica.booking.retention.GuestPiiAnonymisationService;
import com.beautica.booking.retention.GuestPiiRetentionJob;
import com.beautica.booking.retention.GuestPiiRetentionProperties;
import com.beautica.review.invite.service.GuestReviewRetentionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 402 D3 — the nightly {@link GuestPiiRetentionJob} run against real Postgres with a FIXED clock: one
 * {@code run()} anonymises the due guest data (its review → «Гість», its pending invite → {@code SKIPPED (ANONYMISED)})
 * and then purges exactly the terminal invites whose clock is strictly before {@code now − 30 d}, across visit- and
 * booking-keyed rows, in several {@code LIMIT}-bounded batches — and nothing else.
 *
 * <p>The fixed {@code now} lies in 2027 and every seeded timestamp lies after the real wall clock, so a purge keyed
 * on wall time (instead of the job's injected {@link Clock}) would delete nothing and fail the test. Each boundary
 * row sits one second either side of the cutoff ({@code consumed_at} exactly AT the cutoff is kept: strict
 * {@code <}).
 *
 * <p>Same {@code @TestPropertySource} as {@code GuestReviewSubmitIT}, so the Spring context is shared.
 */
@TestPropertySource(properties = {
        "app.booking.sms.enabled=true",
        "app.booking.review-invite.enabled=true",
        "app.booking.review-invite.min-policy-version=2026-10-20"
})
@DisplayName("GuestPiiRetentionJob nightly run — anonymise + invite purge, fixed clock — phase 402 IT")
class GuestPiiRetentionJobInvitePurgeIT extends AbstractReviewInviteIT {

    private static final Instant NOW = Instant.parse("2027-06-15T03:27:00Z");
    private static final Instant CUTOFF = NOW.minus(GuestReviewRetentionService.INVITE_RETENTION);
    private static final Duration SECOND = Duration.ofSeconds(1);
    /** Batch of 2 with 5 due rows: the run needs three batches (2 + 2 + 1). */
    private static final GuestPiiRetentionProperties PROPERTIES =
            new GuestPiiRetentionProperties(true, "0 27 3 * * *", 12, 2, 50);

    @Autowired private GuestPiiAnonymisationService anonymisationService;
    @Autowired private GuestReviewRetentionService retentionService;
    @Autowired private ApplicationContext applicationContext;

    private GuestPiiRetentionJob job() {
        return new GuestPiiRetentionJob(anonymisationService, retentionService, PROPERTIES,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private UUID visit() {
        return fixtures.linkVisit("COMPLETED", GUEST_PHONE, POLICY);
    }

    /** A recent single LINK booking — never due for anonymisation at the fixed cutoff (2026-06-15). */
    private UUID booking() {
        return fixtures.singleLinkBooking(masterId, masterServiceId, "COMPLETED", GUEST_PHONE, POLICY, 5);
    }

    private static byte[] hash32() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        return ByteBuffer.allocate(32).putLong(a.getMostSignificantBits()).putLong(a.getLeastSignificantBits())
                .putLong(b.getMostSignificantBits()).putLong(b.getLeastSignificantBits()).array();
    }

    /** {@code SCHEDULED} / {@code SKIPPED} / {@code FAILED} invite created (and due) at {@code createdAt}. */
    private UUID unsent(String subjectColumn, UUID subjectId, String status, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO review_invites (id, " + subjectColumn + ", phone_hash, status, send_after, "
                        + "skip_reason, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, subjectId, hash32(), status, Timestamp.from(createdAt),
                "SKIPPED".equals(status) ? "STALE" : null, Timestamp.from(createdAt));
        return id;
    }

    /** {@code SENT} / {@code EXPIRED} / {@code CONSUMED} invite sent 14 days before {@code expiresAt}. */
    private UUID sent(String subjectColumn, UUID subjectId, String status, Instant expiresAt, Instant consumedAt) {
        UUID id = UUID.randomUUID();
        Instant sentAt = expiresAt.minus(Duration.ofDays(14));
        jdbcTemplate.update("INSERT INTO review_invites (id, " + subjectColumn + ", phone_hash, token_hash, status, "
                        + "send_after, sent_at, expires_at, consumed_at, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, subjectId, hash32(), hash32(), status, Timestamp.from(sentAt), Timestamp.from(sentAt),
                Timestamp.from(expiresAt), consumedAt == null ? null : Timestamp.from(consumedAt),
                Timestamp.from(sentAt));
        return id;
    }

    private java.util.List<UUID> remainingInviteIds() {
        return jdbcTemplate.queryForList("SELECT id FROM review_invites", UUID.class);
    }

    @Test
    @DisplayName("one run: due booking anonymised (review «Гість», invite SKIPPED ANONYMISED) and exactly the 5 rows "
            + "1 s past the 30-day cutoff purged across both subjects in 3 batches; rows at/after the cutoff kept")
    void should_anonymiseThenPurgeExactlyDueRows_when_nightlyJobRunsWithFixedClock() {
        // due for anonymisation at the fixed cutoff (now − 12 months = 2026-06-15): ended ~200 days before today
        UUID dueBooking = fixtures.singleLinkBooking(masterId, masterServiceId, "COMPLETED", GUEST_PHONE, POLICY,
                24 * 200);
        UUID dueReview = fixtures.review(dueBooking, null, masterId, "GUEST_LINK", "Марія Ш.");
        UUID dueInvite = unsent("booking_id", dueBooking, "SCHEDULED", NOW.minus(Duration.ofDays(1)));
        // purged: terminal clock one second before the cutoff
        UUID consumedVisit = sent("appointment_id", visit(), "CONSUMED", CUTOFF.plus(Duration.ofDays(1)),
                CUTOFF.minus(SECOND));
        UUID expiredBooking = sent("booking_id", booking(), "EXPIRED", CUTOFF.minus(SECOND), null);
        UUID stalledSentVisit = sent("appointment_id", visit(), "SENT", CUTOFF.minus(SECOND), null);
        UUID skippedBooking = unsent("booking_id", booking(), "SKIPPED", CUTOFF.minus(SECOND));
        UUID failedVisit = unsent("appointment_id", visit(), "FAILED", CUTOFF.minus(SECOND));
        // kept: terminal clock at or after the cutoff, or never-terminal
        UUID consumedAtCutoff = sent("booking_id", booking(), "CONSUMED", CUTOFF.plus(Duration.ofDays(1)), CUTOFF);
        UUID expiredRecent = sent("appointment_id", visit(), "EXPIRED", CUTOFF.plus(Duration.ofHours(1)), null);
        UUID liveSent = sent("booking_id", booking(), "SENT", NOW.plus(Duration.ofDays(1)), null);
        UUID skippedRecent = unsent("appointment_id", visit(), "SKIPPED", CUTOFF.plus(SECOND));
        UUID failedRecent = unsent("booking_id", booking(), "FAILED", CUTOFF.plus(SECOND));
        UUID oldScheduled = unsent("appointment_id", visit(), "SCHEDULED", CUTOFF.minus(Duration.ofDays(100)));

        job().run();

        assertThat(jdbcTemplate.queryForObject("SELECT guest_display_name FROM reviews WHERE id = ?", String.class,
                dueReview)).as("due booking's review anonymised by the same run").isEqualTo(GuestPii.ANONYMISED_NAME);
        assertThat(inviteRow(dueInvite)).as("due booking's pending invite, created after the purge cutoff")
                .containsEntry("status", "SKIPPED").containsEntry("skip_reason", "ANONYMISED");
        assertThat(remainingInviteIds()).as("purged: %s", java.util.List.of(consumedVisit, expiredBooking,
                        stalledSentVisit, skippedBooking, failedVisit))
                .containsExactlyInAnyOrder(dueInvite, consumedAtCutoff, expiredRecent, liveSent, skippedRecent,
                        failedRecent, oldScheduled);
    }

    @Test
    @DisplayName("D3 sweeps: a visit header anonymised by the ended-visit sweep and one by the leftover-header sweep "
            + "→ both SCHEDULED invites SKIPPED (ANONYMISED); a recent header's invite stays SCHEDULED")
    void should_skipVisitInvites_when_headersAnonymisedBySweeps() {
        // ended sweep: its only item was anonymised earlier (inside the 30-day lookback) and ended before the cutoff
        UUID endedVisit = visit();
        UUID endedItem = fixtures.visitItem(endedVisit, masterId, masterServiceId, "LINK", "COMPLETED", GUEST_PHONE,
                24 * 200);
        jdbcTemplate.update("UPDATE bookings SET guest_name = ?, guest_surname = NULL, guest_phone = NULL, "
                        + "cancel_token_hash = NULL, guest_pii_anonymised_at = ? WHERE id = ?",
                GuestPii.ANONYMISED_NAME, Timestamp.from(NOW.minus(Duration.ofHours(1))), endedItem);
        // leftover sweep: an item-less header created before the cutoff (now − 12 months = 2026-06-15)
        UUID leftoverVisit = visit();
        jdbcTemplate.update("UPDATE appointments SET created_at = ? WHERE id = ?",
                Timestamp.from(Instant.parse("2026-01-10T10:00:00Z")), leftoverVisit);
        UUID recentVisit = visit();
        Instant sendAfter = NOW.minus(Duration.ofDays(1));
        UUID endedInvite = unsent("appointment_id", endedVisit, "SCHEDULED", sendAfter);
        UUID leftoverInvite = unsent("appointment_id", leftoverVisit, "SCHEDULED", sendAfter);
        UUID recentInvite = unsent("appointment_id", recentVisit, "SCHEDULED", sendAfter);

        job().run();

        assertThat(jdbcTemplate.queryForObject("SELECT guest_pii_anonymised_at IS NOT NULL FROM appointments "
                + "WHERE id = ?", Boolean.class, endedVisit)).as("pre-condition: ended sweep took the header").isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT guest_pii_anonymised_at IS NOT NULL FROM appointments "
                + "WHERE id = ?", Boolean.class, leftoverVisit)).as("pre-condition: leftover sweep took the header")
                .isTrue();
        assertThat(inviteRow(endedInvite)).containsEntry("status", "SKIPPED").containsEntry("skip_reason", "ANONYMISED");
        assertThat(inviteRow(leftoverInvite)).containsEntry("status", "SKIPPED")
                .containsEntry("skip_reason", "ANONYMISED");
        assertThat(inviteRow(recentInvite)).containsEntry("status", "SCHEDULED");
    }

    @Test
    @DisplayName("a second run at the same clock purges nothing more (idempotent) and leaves the kept rows alone")
    void should_purgeNothingMore_when_jobRunsTwiceAtSameClock() {
        unsent("booking_id", booking(), "SKIPPED", CUTOFF.minus(SECOND));
        UUID kept = unsent("appointment_id", visit(), "FAILED", CUTOFF.plus(SECOND));
        job().run();

        job().run();

        assertThat(remainingInviteIds()).containsExactly(kept);
    }

    @Test
    @DisplayName("retention flag off (test profile: app.guest-pii-retention.enabled=false) → no job bean, so no "
            + "scheduled run can purge invites")
    void should_haveNoJobBean_when_retentionFlagOff() {
        assertThat(applicationContext.getEnvironment().getProperty("app.guest-pii-retention.enabled"))
                .isEqualTo("false");

        assertThat(applicationContext.getBeanProvider(GuestPiiRetentionJob.class).getIfAvailable()).isNull();
    }
}
