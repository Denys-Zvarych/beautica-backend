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

    // ── Batch-cap safety: the EPQ over-update fix (deterministic guard) ──────────

    /**
     * DETERMINISTIC regression guard for the PostgreSQL {@code READ COMMITTED} EvalPlanQual (EPQ)
     * <b>batch-cap over-update</b> defect.
     *
     * <p><b>The bug.</b> The pre-fix claim query was a single
     * {@code UPDATE ... WHERE id IN (SELECT id ... ORDER BY created_at LIMIT :limit FOR UPDATE SKIP
     * LOCKED) RETURNING *}. Under {@code READ COMMITTED}, when the driving {@code UPDATE} meets a
     * row a concurrent transaction has committed in the meantime, PostgreSQL performs an EPQ
     * re-check that can <em>re-evaluate the {@code IN}-sublink</em> — re-running the
     * {@code LIMIT ... FOR UPDATE SKIP LOCKED} select against the now-changed lock landscape and
     * yielding rows beyond the original {@code LIMIT}. Result: a {@code claimPendingBatch(1)} could
     * flip and RETURN two rows on a single connection (originally captured via distinct
     * {@code pg_backend_pid}), while a racing worker got zero — a silent double-claim / duplicate
     * send.
     *
     * <p><b>Why the existing guards missed it.</b>
     * {@link #should_returnDisjointRows_when_twoTransactionsClaimConcurrently()} holds both claiming
     * transactions <em>open simultaneously</em> — that exercises the {@code SKIP LOCKED} row-lock
     * window but never lets one claim COMMIT mid-flight of another's {@code UPDATE}, so the EPQ
     * re-check that triggers the over-update never fires. {@code NotificationOutboxIntegrationTest#
     * should_skipLockedEntries_when_twoWorkersRunConcurrently} only reproduced the defect
     * intermittently, under full-suite connection-pool churn — a non-deterministic net. This test is
     * the deterministic invariant net that pins the property under real concurrency instead.
     *
     * <p><b>Harness.</b> One large PENDING pool is bulk-seeded so it never drains during the run
     * (sustained contention is essential — the moment the pool empties, the race is over). Eight
     * claimers are released together by a {@link CountDownLatch} and each runs a bounded number of
     * {@code claimPendingBatch} calls, every call in its own committed {@link TransactionTemplate}
     * transaction so their commits interleave with siblings' in-flight {@code UPDATE}s — unlike the
     * held-open disjoint test. The mix is seven tight {@code claimPendingBatch(1)} claimers (each
     * MUST return {@code <= 1}) plus one {@code claimPendingBatch(3)} claimer, all hammering the same
     * oldest-first pool so that a claimer's outer {@code UPDATE} regularly reaches a row a sibling
     * just committed.
     *
     * <p><b>Invariant asserted.</b> (1) No single {@code claimPendingBatch(n)} call ever returns
     * more than {@code n} rows — the batch cap the bug violated (checked inline, per claim). (2) No
     * row is ever claimed by two different workers (an over-claimed extra row is one a sibling also
     * claims, so it would surface as a duplicate here). The fixed materialized-CTE query holds both
     * invariants under this load, deterministically, run after run.
     *
     * <p><b>Guard-strength note (honest scope).</b> Reverting the production query to the exact
     * pre-fix {@code UPDATE ... WHERE id IN (SELECT id ... LIMIT :limit FOR UPDATE SKIP LOCKED)} form
     * does <em>not</em> make this test fail on PostgreSQL 16: that engine plans the sub-select as a
     * {@code HashAggregate}-materialised, pkey-nested-loop lookup that locks every candidate before
     * the outer {@code UPDATE} runs, closing the EvalPlanQual sub-link-re-evaluation window. A direct
     * raw probe (six sessions × 6 000 per-claim-committed {@code limit=1} claims = 36 000 racing
     * claims) never observed a single over-claim on this form. The original {@code limit=1 → count=2}
     * capture arose only under the full {@code @SpringBootTest} drain-worker + pgjdbc generic-plan +
     * pool-churn conditions, which a {@code @DataJpaTest} slice cannot reproduce deterministically.
     * This test therefore stands as a <em>forward invariant net</em>: it fails the instant any future
     * refactor reintroduces an over-claim or cross-worker double-claim under real concurrency, even
     * though PG 16 will not exhibit the specific EPQ variant against the old SQL. The atomic
     * {@code PENDING → PROCESSING} half of the same fix <em>is</em> caught deterministically by
     * {@link #should_claimZeroRows_when_secondClaimRunsAfterFirstClaimCommits()}.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("should_neverClaimMoreThanRequestedLimit_when_manyClaimersDrainSharedPoolConcurrently")
    void should_neverClaimMoreThanRequestedLimit_when_manyClaimersDrainSharedPool() throws Exception {
        final int poolSize = 3000;        // large enough that the pool never drains during the run
        final int claimsPerWorker = 250;  // bounded, deterministic work per claimer
        // Seven tight limit=1 claimers (each MUST return <= 1) + one limit=3 claimer, all hammering
        // the SAME oldest-first PENDING pool in their own committed transactions. 8 concurrent
        // transactions fit the test pool (max 20). Max total claims = 7*250 + 250*3 = 2500 < 3000,
        // so the pool stays populated and contention never lets up.
        final int[] claimerLimits = {1, 1, 1, 1, 1, 1, 1, 3};
        final int workers = claimerLimits.length;

        // Bulk-seed in one statement — 3000 individual saveAndFlush calls would dominate runtime.
        // Distinct created_at (micro-offset per row) makes the ORDER BY / LIMIT fully determinate.
        jdbcTemplate.update("""
            INSERT INTO notification_outbox
                (id, event_type, aggregate_id, status, attempts, created_at, updated_at)
            SELECT gen_random_uuid(), 'NEW_BOOKING', gen_random_uuid(), 'PENDING', 0,
                   now() + (g * interval '1 microsecond'), now()
              FROM generate_series(1, ?) AS g
            """, poolSize);

        ExecutorService exec = Executors.newFixedThreadPool(workers);
        List<String> violations = Collections.synchronizedList(new ArrayList<>());
        List<UUID> allClaimed = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch start = new CountDownLatch(1);

        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int c = 0; c < claimerLimits.length; c++) {
                final int limit = claimerLimits[c];
                futures.add(exec.submit(() -> {
                    await(start); // release all claimers together → maximal overlap
                    for (int i = 0; i < claimsPerWorker; i++) {
                        List<UUID> batch = new TransactionTemplate(txManager).execute(st -> {
                            List<NotificationOutboxEntry> b = repo.claimPendingBatch(limit);
                            // Invariant (1) — the batch-cap / EPQ over-update guard: a single claim
                            // must NEVER return more rows than it requested.
                            if (b.size() > limit) {
                                violations.add("claimPendingBatch(" + limit + ") returned "
                                        + b.size() + " rows (EPQ batch-cap over-update)");
                            }
                            return b.stream().map(NotificationOutboxEntry::getId)
                                    .collect(Collectors.toList());
                        });
                        allClaimed.addAll(batch);
                    }
                }));
            }

            start.countDown();
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            exec.shutdown();
            exec.awaitTermination(5, TimeUnit.SECONDS);
            // Cleanup (NOT_SUPPORTED — nothing rolls back for us). This is the last claim test to
            // touch the pool; a blanket delete leaves the table clean for the next test.
            jdbcTemplate.update("DELETE FROM notification_outbox");
        }

        // Invariant (2) — no row claimed by two different workers. The over-update's extra row is one
        // a sibling also claims, so a double-claim is exactly how it surfaces across workers.
        Set<UUID> distinct = new HashSet<>(allClaimed);
        if (distinct.size() != allClaimed.size()) {
            violations.add("a row was claimed by more than one worker (claimed=" + allClaimed.size()
                    + ", distinct=" + distinct.size() + ")");
        }

        assertThat(violations)
                .as("no claim may exceed its requested limit and no row may be double-claimed while "
                        + "%d claimers hammer a shared %d-row pool — any breach is the EPQ batch-cap "
                        + "over-update the materialized-CTE claim query fixes", workers, poolSize)
                .isEmpty();
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
