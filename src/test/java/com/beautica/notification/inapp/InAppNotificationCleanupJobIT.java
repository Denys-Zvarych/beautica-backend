package com.beautica.notification.inapp;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.BookingTestFixtures;
import com.beautica.config.TestSecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 335 — real Postgres (Testcontainers) integration tests for {@link
 * InAppNotificationCleanupJob}'s retention sweep.
 *
 * <p>{@code notification.inapp.cleanup.enabled=true} is set via {@code @TestPropertySource} so
 * the job bean is provably wired and actually deletes in a real Spring context — mirrors {@code
 * ClosureReminderJobIT}'s exact posture for {@code booking.closure-reminder.enabled}. {@code
 * batch-size=5} / {@code max-batches-per-run=3} (a 15-row per-run budget) are deliberately small
 * so the batching and budget-cap tests use a fixture size a human can read, rather than seeding
 * 1000+ rows to exercise the production defaults — the loop shape is identical at any size.
 *
 * <p>Own class-local frozen {@link Clock} ({@code NOW = 2026-09-28T04:12:00Z}), not shared with
 * any sibling suite's frozen-clock config (Phase 28.3's isolation mechanism, same as {@code
 * BookingUnclosedCountIT}).
 */
@Import({TestSecurityConfig.class, InAppNotificationCleanupJobIT.FrozenClockConfig.class})
@TestPropertySource(properties = {
        "notification.inapp.cleanup.enabled=true",
        "notification.inapp.retention-days=90",
        "notification.inapp.cleanup.batch-size=5",
        "notification.inapp.cleanup.max-batches-per-run=3"
})
@DisplayName("InAppNotificationCleanupJob — integration (phase 335)")
class InAppNotificationCleanupJobIT extends AbstractIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-28T04:12:00Z");
    private static final Duration RETENTION = Duration.ofDays(90);
    private static final Instant CUTOFF = NOW.minus(RETENTION);

    @TestConfiguration
    static class FrozenClockConfig {
        @Bean
        Clock systemClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    private InAppNotificationCleanupJob job;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private BookingTestFixtures fixtures;
    private UUID userId;

    @BeforeEach
    void setUp() {
        fixtures = new BookingTestFixtures(null, jdbcTemplate, null, passwordEncoder);
        userId = fixtures.createUser("inapp-retention-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
    }

    // -------------------------------------------------------------------------
    // Wiring
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("job bean is present in a real Spring context")
    void should_exposeJobBean_when_realSpringContext() {
        assertThat(job).isNotNull();
    }

    // -------------------------------------------------------------------------
    // Age-based delete/keep, both read and unread
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("rows at 91 days (read and unread) are deleted; a row at 89 days is kept")
    void should_deleteOlderThanRetention_keepNewer() {
        UUID oldRead = seedRow(NOW.minus(Duration.ofDays(91)), true);
        UUID oldUnread = seedRow(NOW.minus(Duration.ofDays(91)), false);
        UUID recent = seedRow(NOW.minus(Duration.ofDays(89)), false);

        job.sweep();

        assertThat(rowExists(oldRead)).as("91-day-old read row must be deleted").isFalse();
        assertThat(rowExists(oldUnread)).as("91-day-old unread row must be deleted").isFalse();
        assertThat(rowExists(recent)).as("89-day-old row must be kept").isTrue();
    }

    @Test
    @DisplayName("a row exactly at the cutoff instant is kept (strict less-than boundary)")
    void should_keepRow_when_createdAtExactlyAtCutoff() {
        UUID atCutoff = seedRow(CUTOFF, false);

        job.sweep();

        assertThat(rowExists(atCutoff)).as("created_at == cutoff must NOT be deleted (< is strict)").isTrue();
    }

    @Test
    @DisplayName("a row one second before the cutoff instant is deleted")
    void should_deleteRow_when_createdAtOneSecondBeforeCutoff() {
        UUID justPastCutoff = seedRow(CUTOFF.minusSeconds(1), false);

        job.sweep();

        assertThat(rowExists(justPastCutoff)).as("created_at one second before cutoff must be deleted").isFalse();
    }

    // -------------------------------------------------------------------------
    // Batching and the per-run budget cap
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("batching: 12 old rows with batchSize=5 are all deleted across multiple batches (under the 15-row budget)")
    void should_deleteInBatches_when_backlogExceedsBatchSize() {
        java.util.List<UUID> ids = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) {
            ids.add(seedRow(NOW.minus(Duration.ofDays(91)), false));
        }

        job.sweep();

        for (UUID id : ids) {
            assertThat(rowExists(id)).as("row %s must be deleted", id).isFalse();
        }
        assertThat(countRows()).isZero();
    }

    @Test
    @DisplayName("budget cap: 20 old rows exceed the 15-row per-run budget (batchSize=5 x maxBatches=3); "
            + "the first run stops at 15, the second run finishes the remaining 5")
    void should_stopAtBudget_when_backlogExceedsPerRunCap_and_finishOnNextRun() {
        for (int i = 0; i < 20; i++) {
            seedRow(NOW.minus(Duration.ofDays(91)), false);
        }
        assertThat(countRows()).isEqualTo(20);

        job.sweep();
        assertThat(countRows())
                .as("first run must stop at the 15-row budget (batchSize=5 x maxBatchesPerRun=3), leaving 5")
                .isEqualTo(5);

        job.sweep();
        assertThat(countRows())
                .as("second run must finish the remaining backlog — nothing is ever lost, only delayed")
                .isZero();
    }

    /**
     * QA follow-up (2026-09-28) — {@code should_stopAtBudget_when_backlogExceedsPerRunCap_and_
     * finishOnNextRun} above pins the ROW-COUNT half of the budget-cap contract (first run stops at
     * 15, second run finishes the remaining 5) but never asserted the WARN log the job's own javadoc
     * promises — that assertion only existed at the plain-Mockito unit level ({@code
     * InAppNotificationCleanupJobTest#should_logWarn_when_runStopsAtMaxBatchesPerRun}), never against
     * a REAL Spring context with the real Logback pipeline wired. This closes that gap.
     */
    @Test
    @DisplayName("budget cap: the first real sweep() run that stops at maxBatchesPerRun logs a WARN "
            + "containing 'backlog not fully drained'; the second run, which finishes the backlog on "
            + "its own, logs no such WARN")
    void should_logWarn_when_realSweepStopsAtBudgetCap_and_notOnTheFollowUpRun() {
        for (int i = 0; i < 20; i++) {
            seedRow(NOW.minus(Duration.ofDays(91)), false);
        }

        Logger jobLogger = (Logger) LoggerFactory.getLogger(InAppNotificationCleanupJob.class);
        ListAppender<ILoggingEvent> logAppender = new ListAppender<>();
        logAppender.start();
        jobLogger.addAppender(logAppender);
        jobLogger.setLevel(Level.WARN);
        try {
            job.sweep(); // first run — stops at the 15-row budget (batchSize=5 x maxBatchesPerRun=3)
        } finally {
            jobLogger.detachAppender(logAppender);
        }
        assertThat(countRows()).as("sanity — first run really did stop at the budget cap").isEqualTo(5);
        assertThat(logAppender.list)
                .as("the first (budget-capped) real sweep() run must log a WARN naming the undrained "
                        + "backlog")
                .anyMatch(event -> event.getLevel() == Level.WARN
                        && event.getFormattedMessage().contains("backlog not fully drained"));

        ListAppender<ILoggingEvent> secondRunAppender = new ListAppender<>();
        secondRunAppender.start();
        jobLogger.addAppender(secondRunAppender);
        try {
            job.sweep(); // second run — finishes the remaining 5 rows well under the budget
        } finally {
            jobLogger.detachAppender(secondRunAppender);
        }
        assertThat(countRows()).as("sanity — second run finishes the backlog").isZero();
        assertThat(secondRunAppender.list)
                .as("a run that finishes its backlog on its own must log no budget-cap WARN")
                .noneMatch(event -> event.getLevel() == Level.WARN);
    }

    // -------------------------------------------------------------------------
    // No-op on an empty table
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("sweep survives and is a true no-op against an empty table")
    void should_noop_when_empty() {
        assertThat(countRows()).isZero();

        job.sweep();

        assertThat(countRows()).isZero();
    }

    // -------------------------------------------------------------------------
    // Blast radius — nothing outside the retention window, and no other table, is touched
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("other rows and other tables are left untouched: the seeded user survives, and a "
            + "kept (89-day) notification row is unaffected while a 91-day sibling is deleted")
    void should_leaveOtherRowsAndOtherTablesUntouched() {
        UUID old = seedRow(NOW.minus(Duration.ofDays(91)), false);
        UUID kept = seedRow(NOW.minus(Duration.ofDays(10)), false);

        job.sweep();

        assertThat(rowExists(old)).isFalse();
        assertThat(rowExists(kept)).isTrue();
        Long userCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE id = ?", Long.class, userId);
        assertThat(userCount).as("the recipient user row must survive the sweep").isEqualTo(1L);
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    /**
     * Inserts one {@code INVITE_ACCEPTED} feed row directly via JDBC, with an explicit {@code
     * created_at} — the repository's own insert methods leave {@code created_at} to the column's
     * {@code DEFAULT now()} (§O-1), so backdating requires a raw insert. {@code INVITE_ACCEPTED}
     * is used because its shape CHECK (unlike every other type) does not require a real {@code
     * booking_id}/{@code appointment_id} — only {@code subject_user_id} or {@code salon_id} set,
     * satisfied here by reusing the one seeded user as both recipient and subject.
     */
    private UUID seedRow(Instant createdAt, boolean read) {
        UUID id = UUID.randomUUID();
        String dedupKey = "INVITE_ACCEPTED:" + UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO in_app_notification "
                        + "(id, recipient_user_id, type, subject_user_id, dedup_key, created_at, read_at) "
                        + "VALUES (?, ?, 'INVITE_ACCEPTED', ?, ?, ?, ?)",
                id, userId, userId, dedupKey, Timestamp.from(createdAt),
                read ? Timestamp.from(createdAt) : null);
        return id;
    }

    private boolean rowExists(UUID id) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM in_app_notification WHERE id = ?", Long.class, id);
        return count != null && count > 0;
    }

    private long countRows() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM in_app_notification", Long.class);
        return count == null ? 0 : count;
    }
}
