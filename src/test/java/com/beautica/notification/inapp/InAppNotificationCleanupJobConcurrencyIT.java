package com.beautica.notification.inapp;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.BookingTestFixtures;
import com.beautica.config.TestSecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Phase 335 QA follow-up — two concurrent {@link InAppNotificationCleanupJob#sweep()} calls racing
 * the SAME backlog.
 *
 * <p>{@code deleteCreatedBefore} is a bounded {@code DELETE ... WHERE id IN (SELECT ... LIMIT :limit
 * FOR UPDATE SKIP LOCKED)} (Phase 336): two overlapping sweeps each lock a DISJOINT batch, and a row
 * locked by anything else (e.g. an in-flight mark-read UPDATE) is skipped this run and picked up by
 * the next — never an exception, never a double delete, never a young row touched. This class drives
 * two REAL, overlapping {@code sweep()} calls against a shared backlog, and a sweep against a row
 * held by an uncommitted UPDATE, to pin those claims rather than taking the javadoc's word for them.
 *
 * <p>Runs at the {@code @SpringBootTest} level (not {@code @DataJpaTest}) because {@code sweep()}'s
 * batch loop only honours its {@code @Transactional(REQUIRES_NEW)} {@code deleteBatch} when called
 * through the REAL Spring AOP {@code self} proxy — a full context autowires that proxy for free,
 * exactly like {@link InAppNotificationCleanupJobIT}.
 *
 * <p>Backlog seeded via one bulk {@code INSERT ... SELECT FROM generate_series} (mirrors {@code
 * NotificationOutboxConcurrentClaimTest#should_neverClaimMoreThanRequestedLimit_when_
 * manyClaimersDrainSharedPool}'s own bulk-seed convention) rather than a per-row Java loop — 2,500 +
 * 300 individual {@code jdbcTemplate.update} round trips would dominate the test's runtime for no
 * assertion benefit.
 */
@Import(TestSecurityConfig.class)
@TestPropertySource(properties = {
        "notification.inapp.cleanup.enabled=true",
        "notification.inapp.retention-days=90",
        "notification.inapp.cleanup.batch-size=1000",
        "notification.inapp.cleanup.max-batches-per-run=10"
})
@DisplayName("InAppNotificationCleanupJob — two concurrent sweep() calls (phase 335 QA)")
class InAppNotificationCleanupJobConcurrencyIT extends AbstractIntegrationTest {

    @Autowired
    private InAppNotificationCleanupJob job;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private BookingTestFixtures fixtures;
    private UUID userId;

    @BeforeEach
    void setUp() {
        fixtures = new BookingTestFixtures(null, jdbcTemplate, null, passwordEncoder);
        userId = fixtures.createUser("inapp-conc-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
    }

    @Test
    @DisplayName("two threads calling sweep() concurrently against a 2,500-row old backlog "
            + "(batchSize=1000) delete every old row exactly once, raise no exception on either "
            + "thread, and leave 300 young rows completely untouched")
    void should_deleteEveryOldRowExactlyOnce_when_twoThreadsSweepConcurrently() throws Exception {
        Instant now = Instant.now();
        Instant oldCreatedAt = now.minus(Duration.ofDays(91));
        Instant youngCreatedAt = now.minus(Duration.ofDays(10));
        int oldCount = 2500;
        int youngCount = 300;

        seedBulkRows(oldCreatedAt, oldCount);
        seedBulkRows(youngCreatedAt, youngCount);
        assertThat(countRowsForUser()).isEqualTo(oldCount + youngCount);

        ExecutorService exec = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failureA = new AtomicReference<>();
        AtomicReference<Throwable> failureB = new AtomicReference<>();
        try {
            Future<?> futureA = exec.submit(() -> {
                try {
                    start.await();
                    job.sweep();
                } catch (Throwable t) {
                    failureA.set(t);
                }
            });
            Future<?> futureB = exec.submit(() -> {
                try {
                    start.await();
                    job.sweep();
                } catch (Throwable t) {
                    failureB.set(t);
                }
            });
            start.countDown();
            futureA.get(60, TimeUnit.SECONDS);
            futureB.get(60, TimeUnit.SECONDS);
        } finally {
            exec.shutdown();
            exec.awaitTermination(5, TimeUnit.SECONDS);
        }

        assertThat(failureA.get()).as("thread A's sweep() must not throw — body=%s", failureA.get()).isNull();
        assertThat(failureB.get()).as("thread B's sweep() must not throw — body=%s", failureB.get()).isNull();

        assertThat(countRowsCreatedBefore(now.minus(Duration.ofDays(90))))
                .as("every one of the 2,500 old rows must be gone — no exception on either racing "
                        + "thread means neither silently gave up mid-backlog")
                .isZero();
        assertThat(countRowsForUser())
                .as("only the 300 young rows remain — neither concurrent sweep touched a row inside "
                        + "the retention window")
                .isEqualTo(youngCount);
    }

    @Test
    @DisplayName("a row locked by a concurrent mark-read UPDATE is SKIPPED (sweep neither blocks nor "
            + "fails) and is deleted by the NEXT run once the lock is released — no loss, no block")
    void should_skipLockedRowThenDeleteItNextRun_when_markReadHoldsRowLock() throws Exception {
        Instant oldCreatedAt = Instant.now().minus(Duration.ofDays(91));
        seedBulkRows(oldCreatedAt, 3);
        UUID lockedId = jdbcTemplate.queryForObject(
                "SELECT id FROM in_app_notification WHERE recipient_user_id = ? ORDER BY id LIMIT 1",
                UUID.class, userId);

        Connection markRead = jdbcTemplate.getDataSource().getConnection();
        markRead.setAutoCommit(false);
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            try (PreparedStatement ps = markRead.prepareStatement(
                    "UPDATE in_app_notification SET read_at = now() WHERE id = ?")) {
                ps.setObject(1, lockedId);
                assertThat(ps.executeUpdate()).as("the mark-read UPDATE must hold the row lock").isEqualTo(1);
            }

            Future<?> sweep = exec.submit(job::sweep);
            try {
                sweep.get(15, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                fail("sweep() blocked on a row locked by an in-flight mark-read — FOR UPDATE SKIP LOCKED "
                        + "must skip it instead of waiting");
            }

            assertThat(countRowsForUser())
                    .as("run 1 deletes the 2 unlocked old rows and skips the locked one")
                    .isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM in_app_notification WHERE id = ?", Long.class, lockedId))
                    .as("the locked row survives run 1").isEqualTo(1L);

            markRead.commit();
        } finally {
            markRead.close();
            exec.shutdownNow();
        }

        job.sweep();

        assertThat(countRowsForUser())
                .as("run 2 (lock released) deletes the previously skipped row — nothing is lost")
                .isZero();
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    /**
     * Bulk-seeds {@code count} {@code INVITE_ACCEPTED} rows sharing one {@code created_at}, each
     * with its own {@code gen_random_uuid()}-derived {@code dedup_key} so the
     * {@code (recipient_user_id, dedup_key)} unique constraint never collides. {@code
     * INVITE_ACCEPTED} reused for the same shape-CHECK reason as {@code
     * InAppNotificationCleanupJobIT#seedRow}'s javadoc — {@code subject_user_id} alone satisfies it.
     */
    private void seedBulkRows(Instant createdAt, int count) {
        jdbcTemplate.update("""
                INSERT INTO in_app_notification
                    (id, recipient_user_id, type, subject_user_id, dedup_key, created_at, read_at)
                SELECT gen_random_uuid(), ?, 'INVITE_ACCEPTED', ?,
                       'INVITE_ACCEPTED:' || gen_random_uuid()::text, ?, NULL
                  FROM generate_series(1, ?)
                """, userId, userId, Timestamp.from(createdAt), count);
    }

    private long countRowsForUser() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM in_app_notification WHERE recipient_user_id = ?", Long.class, userId);
        return count == null ? 0 : count;
    }

    private long countRowsCreatedBefore(Instant threshold) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM in_app_notification WHERE recipient_user_id = ? AND created_at < ?",
                Long.class, userId, Timestamp.from(threshold));
        return count == null ? 0 : count;
    }
}
