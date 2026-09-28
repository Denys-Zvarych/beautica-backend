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

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 335 QA follow-up — two concurrent {@link InAppNotificationCleanupJob#sweep()} calls racing
 * the SAME backlog.
 *
 * <p>The job's own class javadoc claims this is safe by construction: {@code deleteCreatedBefore}
 * is a plain bounded {@code DELETE ... WHERE id IN (SELECT ... LIMIT :limit)} with no {@code FOR
 * UPDATE SKIP LOCKED} — under READ COMMITTED, when two overlapping transactions each select
 * (uncommitted-visibility) the same candidate id set and try to delete it, whichever commits second
 * simply finds its target rows already gone (Postgres re-checks the row's visibility after the lock
 * wait, sees the row deleted, and skips it) — never an exception, never a double logical delete,
 * never a young row touched. This class is the first test that actually drives two REAL, overlapping
 * {@code sweep()} calls against a shared backlog to pin that claim, rather than taking the javadoc's
 * word for it.
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
