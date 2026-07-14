package com.beautica.notification.repository;

import com.beautica.AbstractDataJpaTest;
import com.beautica.notification.entity.NotificationOutboxEntry;
import com.beautica.notification.entity.OutboxEventType;
import com.beautica.notification.entity.OutboxStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dedicated concurrency-and-crash-safety proofs for
 * {@link NotificationOutboxRepository#claimPendingBatch(int)} and
 * {@link NotificationOutboxRepository#reclaimStaleProcessingRows}.
 *
 * <p>Runs at the {@code @DataJpaTest} slice level (real PostgreSQL via
 * {@link AbstractDataJpaTest}'s shared container) rather than a full {@code @SpringBootTest} —
 * this is pure SQL-locking behaviour, no HTTP/service layer needed (Anti-Bug Playbook §M1).
 *
 * <p><b>Every test method here is {@code @Transactional(propagation = NOT_SUPPORTED)}</b> —
 * mirroring {@code NotificationOutboxRepositoryTest}'s purge-query tests. {@code @DataJpaTest}
 * wraps each test method in its own rollback-only transaction by default; both
 * {@code claimPendingBatch} (MANDATORY) and {@code reclaimStaleProcessingRows} (REQUIRES_NEW)
 * need to see rows that a PRIOR call in the same test already durably committed — which never
 * happens while everything shares one uncommitted default transaction. Suspending it makes every
 * repository call in these tests open (and commit) its own real, independent transaction, exactly
 * modelling the separate Railway instances / separate drain ticks under test. Rows are cleaned up
 * explicitly at the end of each test body (not {@code @AfterEach} — for the same reason: without
 * NOT_SUPPORTED, an {@code @AfterEach} DELETE would itself be swallowed by the per-test rollback).
 *
 * <p><b>Why this class exists (MEDIUM concurrency fix).</b> Before this fix,
 * {@code claimPendingBatch} was a plain {@code SELECT ... FOR UPDATE SKIP LOCKED} that never
 * changed a row's status. {@code NotificationOutboxIntegrationTest#should_skipLockedEntries_when_twoWorkersRunConcurrently}
 * already proved {@code SKIP LOCKED} hands two OVERLAPPING transactions disjoint rows — but that
 * property held even with the old, buggy query, because it only exercises the row-lock window.
 * It does NOT prove the property that actually matters for a Railway rolling deploy: that a row
 * stays unclaimable AFTER the claiming transaction has already committed and its lock has been
 * released. {@link #should_claimZeroRows_when_secondClaimRunsAfterFirstClaimCommits()} is that
 * proof — it is the test that would have caught the original bug, and it FAILS without the
 * atomic {@code PENDING → PROCESSING} flip (reverting the fix to a plain
 * {@code SELECT ... FOR UPDATE SKIP LOCKED} reproduces the double-claim here).
 */
@DisplayName("NotificationOutbox — concurrent claim + stale-claim reclaim")
class NotificationOutboxConcurrentClaimTest extends AbstractDataJpaTest {

    @Autowired
    private NotificationOutboxRepository repo;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager txManager;

    // ── Same-instance concurrency: SKIP LOCKED still yields disjoint rows ───────

    /**
     * Baseline sanity check (mirrors the existing {@code @SpringBootTest}-level proof, now at
     * slice level): two transactions racing the claim query at the exact same moment must never
     * receive the same row. This exercises the {@code FOR UPDATE SKIP LOCKED} subquery, which is
     * unchanged by this fix — it still avoids the two claimers blocking on each other.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("should_returnDisjointRows_when_twoTransactionsClaimConcurrently")
    void should_returnDisjointRows_when_twoTransactionsClaimConcurrently() throws Exception {
        UUID id1 = seedPendingRow();
        UUID id2 = seedPendingRow();

        ExecutorService exec = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch claimed = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        List<Set<UUID>> claimsByThread = Collections.synchronizedList(new ArrayList<>());

        Callable<Void> task = () -> {
            new TransactionTemplate(txManager).execute(status -> {
                await(start);
                List<NotificationOutboxEntry> batch = repo.claimPendingBatch(1);
                claimsByThread.add(batch.stream().map(NotificationOutboxEntry::getId).collect(Collectors.toSet()));
                claimed.countDown();
                await(release);
                return null;
            });
            return null;
        };

        try {
            Future<Void> f1 = exec.submit(task);
            Future<Void> f2 = exec.submit(task);
            start.countDown();
            assertThat(claimed.await(5, TimeUnit.SECONDS))
                    .as("both threads must reach the claim within 5s")
                    .isTrue();
            release.countDown();
            f1.get(5, TimeUnit.SECONDS);
            f2.get(5, TimeUnit.SECONDS);
        } finally {
            exec.shutdown();
            exec.awaitTermination(2, TimeUnit.SECONDS);
        }

        Set<UUID> a = claimsByThread.get(0);
        Set<UUID> b = claimsByThread.get(1);
        assertThat(a).hasSize(1);
        assertThat(b).hasSize(1);
        assertThat(a).as("SKIP LOCKED proof — concurrent claimers must receive disjoint rows")
                .doesNotContainAnyElementsOf(b);

        Set<UUID> union = new HashSet<>(a);
        union.addAll(b);
        assertThat(union).containsExactlyInAnyOrder(id1, id2);

        jdbcTemplate.update("DELETE FROM notification_outbox WHERE id IN (?, ?)", id1, id2);
    }

    // ── Cross-instance safety: the actual MEDIUM fix ─────────────────────────────

    /**
     * THE regression test for the MEDIUM concurrency defect. Simulates the Railway rolling-deploy
     * scenario: two application instances, no lock contention between them because the first
     * claim's transaction has ALREADY committed by the time the second instance's claim runs —
     * exactly the window between {@code NotificationOutboxDrainWorker}'s Phase 1 commit and its
     * Phase 2/3 completion.
     *
     * <p>Without the atomic {@code PENDING → PROCESSING} flip, the row is still {@code PENDING}
     * and unlocked after the first claim's transaction commits, so this second, later, entirely
     * uncontended claim would return it AGAIN — a duplicate send. With the fix, the row is
     * {@code PROCESSING} the instant the first transaction commits, so the second claim's
     * {@code WHERE status = 'PENDING'} predicate excludes it outright.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("should_claimZeroRows_when_secondClaimRunsAfterFirstClaimCommits")
    void should_claimZeroRows_when_secondClaimRunsAfterFirstClaimCommits() {
        // Arrange
        UUID id = seedPendingRow();

        // Act — first claim, in its own transaction that fully commits (simulates
        // NotificationOutboxDrainWorker.claimBatch()'s REQUIRES_NEW Phase 1 returning).
        List<NotificationOutboxEntry> firstClaim = new TransactionTemplate(txManager)
                .execute(status -> repo.claimPendingBatch(10));
        assertThat(firstClaim).as("first claim must pick up the seeded row").hasSize(1);
        assertThat(firstClaim.get(0).getId()).isEqualTo(id);
        assertThat(firstClaim.get(0).getStatus()).isEqualTo(OutboxStatus.PROCESSING);

        // Act — second claim, an entirely separate later transaction (simulates a second Railway
        // instance's drain() tick; no lock contention, no overlap — the first transaction is long
        // since committed).
        List<NotificationOutboxEntry> secondClaim = new TransactionTemplate(txManager)
                .execute(status -> repo.claimPendingBatch(10));

        // Assert — the money assertion: the row must NOT be re-claimable.
        assertThat(secondClaim)
                .as("a row already claimed and committed to PROCESSING must never be re-claimed "
                        + "by a later, non-overlapping claim — this is the duplicate-send bug")
                .isEmpty();

        // And the row is durably PROCESSING in the DB, not silently reverted.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM notification_outbox WHERE id = ?", String.class, id))
                .isEqualTo("PROCESSING");

        jdbcTemplate.update("DELETE FROM notification_outbox WHERE id = ?", id);
    }

    // ── Stale-claim reclaim: crash recovery ──────────────────────────────────────

    /**
     * Full crash-recovery narrative: a row is claimed (as if by an instance that then crashed
     * before persisting a dispatch outcome), its {@code updated_at} is backdated past the stale
     * threshold (simulating time passing with no Phase 3 write), and
     * {@link NotificationOutboxRepository#reclaimStaleProcessingRows} resets it — after which a
     * fresh {@code claimPendingBatch} call proves the row is genuinely claimable again, not just
     * flipped in isolation.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("should_recoverStrandedProcessingRow_when_reclaimRunsAfterSimulatedCrash")
    void should_recoverStrandedProcessingRow_when_reclaimRunsAfterSimulatedCrash() {
        // Arrange — claim the row (moves it to PROCESSING), simulating the crashed instance's
        // Phase 1. No Phase 3 write ever happens — that's the "crash".
        UUID id = seedPendingRow();
        List<NotificationOutboxEntry> claimed = new TransactionTemplate(txManager)
                .execute(status -> repo.claimPendingBatch(10));
        assertThat(claimed).hasSize(1);

        // Backdate updated_at to simulate the row having been stuck in PROCESSING well past the
        // stale-claim threshold (raw JDBC — Hibernate's @UpdateTimestamp would otherwise stamp
        // "now" on any ORM-mediated write).
        Instant staleUpdatedAt = Instant.now().minus(Duration.ofHours(2));
        jdbcTemplate.update("UPDATE notification_outbox SET updated_at = ? WHERE id = ?",
                java.sql.Timestamp.from(staleUpdatedAt), id);

        // Act — reclaim sweep with a 1-hour threshold; the row is 2 hours stale, so it qualifies.
        Instant staleBefore = Instant.now().minus(Duration.ofHours(1));
        int reclaimedCount = repo.reclaimStaleProcessingRows(staleBefore, 3, "test-reclaim");
        assertThat(reclaimedCount).isEqualTo(1);

        // Assert — the row is genuinely reclaimable again, proven by actually re-claiming it
        // through the normal claim path (not just asserting its raw column value).
        List<NotificationOutboxEntry> reClaimed = new TransactionTemplate(txManager)
                .execute(status -> repo.claimPendingBatch(10));
        assertThat(reClaimed)
                .as("a reclaimed row must be genuinely claimable again via the normal claim path")
                .hasSize(1);
        assertThat(reClaimed.get(0).getId()).isEqualTo(id);
        assertThat(reClaimed.get(0).getAttempts())
                .as("the reclaim itself counts as one delivery attempt")
                .isEqualTo(1);

        jdbcTemplate.update("DELETE FROM notification_outbox WHERE id = ?", id);
    }

    /**
     * A {@code PROCESSING} row updated recently (well within the stale threshold) represents a
     * live instance still legitimately dispatching it. The reclaim sweep must leave it alone —
     * and a subsequent claim attempt must still find nothing to claim, proving the row is
     * neither reclaimed NOR independently re-claimable while genuinely in flight.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("should_leaveRowClaimed_when_processingRowIsNotYetStale")
    void should_leaveRowClaimed_when_processingRowIsNotYetStale() {
        // Arrange
        UUID id = seedPendingRow();
        List<NotificationOutboxEntry> claimed = new TransactionTemplate(txManager)
                .execute(status -> repo.claimPendingBatch(10));
        assertThat(claimed).hasSize(1);

        // Act — reclaim with a threshold the freshly-claimed row does not satisfy.
        Instant staleBefore = Instant.now().minus(Duration.ofHours(1));
        int reclaimedCount = repo.reclaimStaleProcessingRows(staleBefore, 3, "should-not-apply");

        // Assert
        assertThat(reclaimedCount).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM notification_outbox WHERE id = ?", String.class, id))
                .isEqualTo("PROCESSING");

        jdbcTemplate.update("DELETE FROM notification_outbox WHERE id = ?", id);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private UUID seedPendingRow() {
        NotificationOutboxEntry saved = repo.saveAndFlush(NotificationOutboxEntry.builder()
                .eventType(OutboxEventType.NEW_BOOKING)
                .aggregateId(UUID.randomUUID())
                .build());
        return saved.getId();
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
