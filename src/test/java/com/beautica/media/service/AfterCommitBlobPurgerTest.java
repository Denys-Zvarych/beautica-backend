package com.beautica.media.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.beautica.config.AsyncConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P-M2 — {@link AfterCommitBlobPurger}: purges fire only after commit, and with the PRODUCTION
 * {@code blobPurgeExecutor} (built from {@link AsyncConfig}'s own factory method, not the test-profile sync
 * stand-in) the R2 call runs on a {@code blob-purge-} worker, never on the committing thread.
 */
@DisplayName("AfterCommitBlobPurger — after-commit, off-thread R2 purge")
class AfterCommitBlobPurgerTest {

    private final R2StorageService r2 = mock(R2StorageService.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(AfterCommitBlobPurger.class)).addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        ((Logger) LoggerFactory.getLogger(AfterCommitBlobPurger.class)).detachAppender(logs);
    }

    @Test
    @DisplayName("production wiring: afterCommit hands the R2 delete to a 'blob-purge-' worker thread")
    void should_runR2DeleteOnBlobPurgeThread_when_productionExecutorWired() throws Exception {
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) new AsyncConfig().blobPurgeExecutor();
        AtomicReference<String> r2Thread = new AtomicReference<>();
        CountDownLatch deleted = new CountDownLatch(1);
        doAnswer(inv -> {
            r2Thread.set(Thread.currentThread().getName());
            deleted.countDown();
            return Set.of();
        }).when(r2).deleteFiles(anyCollection());
        AfterCommitBlobPurger purger = new AfterCommitBlobPurger(r2, executor, meters);
        TransactionSynchronizationManager.initSynchronization();

        try {
            purger.purgeAfterCommit(List.of("avatars/u/a.jpg"), "test");
            fireAfterCommit();

            assertThat(deleted.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdown();
        }

        assertThat(r2Thread.get()).startsWith("blob-purge-").isNotEqualTo(Thread.currentThread().getName());
    }

    @Test
    @DisplayName("nothing is purged before commit, and a rollback purges nothing")
    void should_notPurge_when_notCommittedOrRolledBack() {
        AfterCommitBlobPurger purger = new AfterCommitBlobPurger(r2, new SyncTaskExecutor(), meters);
        TransactionSynchronizationManager.initSynchronization();

        purger.purgeAfterCommit(List.of("avatars/u/a.jpg"), "test");
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }

        verify(r2, never()).deleteFiles(anyCollection());
    }

    @Test
    @DisplayName("a rejected submit is dropped and WARN-logged with a key COUNT, never a key, and never throws")
    void should_logCountWithoutKeys_when_executorRejects() {
        TaskExecutor rejecting = task -> {
            throw new TaskRejectedException("full");
        };
        AfterCommitBlobPurger purger = new AfterCommitBlobPurger(r2, rejecting, meters);

        assertThatCode(() -> purger.dispatch(List.of("avatars/secret-id/a.jpg"), "test"))
                .doesNotThrowAnyException();

        verify(r2, never()).deleteFiles(anyCollection());
        assertThat(logs.list).filteredOn(e -> e.getLevel() == Level.WARN).singleElement()
                .satisfies(e -> assertThat(e.getFormattedMessage()).contains("keyCount=1").doesNotContain("secret-id"));
    }

    @Test
    @DisplayName("an R2 failure inside the task is swallowed and WARN-logged without keys")
    void should_swallowR2Failure_when_deleteThrows() {
        doThrow(new IllegalStateException("r2 down")).when(r2).deleteFiles(anyCollection());
        AfterCommitBlobPurger purger = new AfterCommitBlobPurger(r2, new SyncTaskExecutor(), meters);

        assertThatCode(() -> purger.dispatch(List.of("avatars/secret-id/a.jpg"), "test")).doesNotThrowAnyException();

        assertThat(logs.list).filteredOn(e -> e.getLevel() == Level.WARN).singleElement()
                .satisfies(e -> assertThat(e.getFormattedMessage()).doesNotContain("secret-id"));
    }

    @Test
    @DisplayName("partial R2 failure is logged as a count only")
    void should_logFailedCount_when_someKeysFail() {
        when(r2.deleteFiles(anyCollection())).thenReturn(Set.of("avatars/secret-id/a.jpg"));
        AfterCommitBlobPurger purger = new AfterCommitBlobPurger(r2, new SyncTaskExecutor(), meters);

        purger.dispatch(List.of("avatars/secret-id/a.jpg", "avatars/secret-id/b.jpg"), "test");

        assertThat(logs.list).filteredOn(e -> e.getLevel() == Level.WARN).singleElement()
                .satisfies(e -> assertThat(e.getFormattedMessage()).contains("failedCount=1").doesNotContain("secret-id"));
    }

    @Test
    @DisplayName("empty key list is a no-op (no registration, no R2 call)")
    void should_doNothing_when_noKeys() {
        AfterCommitBlobPurger purger = new AfterCommitBlobPurger(r2, new SyncTaskExecutor(), meters);
        TransactionSynchronizationManager.initSynchronization();

        purger.purgeAfterCommit(List.of(), "test");

        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        verify(r2, never()).deleteFiles(anyCollection());
    }

    @Test
    @DisplayName("a rejected submit increments beautica.blob_purge.rejected.keys by the dropped key count")
    void should_countRejectedKeys_when_executorRejects() {
        TaskExecutor rejecting = task -> {
            throw new TaskRejectedException("full");
        };
        AfterCommitBlobPurger purger = new AfterCommitBlobPurger(r2, rejecting, meters);

        purger.dispatch(List.of("avatars/u/a.jpg", "avatars/u/b.jpg", "avatars/u/c.jpg"), "test");

        assertThat(counter(AfterCommitBlobPurger.REJECTED_KEYS_METRIC)).isEqualTo(3.0);
        assertThat(counter(AfterCommitBlobPurger.FAILED_KEYS_METRIC)).isZero();
    }

    @Test
    @DisplayName("failed R2 deletes increment beautica.blob_purge.failed.keys: failed subset, or the whole batch on throw")
    void should_countFailedKeys_when_r2DeleteFailsPartiallyOrThrows() {
        AfterCommitBlobPurger purger = new AfterCommitBlobPurger(r2, new SyncTaskExecutor(), meters);
        when(r2.deleteFiles(anyCollection()))
                .thenReturn(Set.of("avatars/u/a.jpg"))
                .thenThrow(new IllegalStateException("r2 down"));

        purger.dispatch(List.of("avatars/u/a.jpg", "avatars/u/b.jpg"), "test");
        purger.dispatch(List.of("avatars/u/c.jpg", "avatars/u/d.jpg"), "test");

        assertThat(counter(AfterCommitBlobPurger.FAILED_KEYS_METRIC)).isEqualTo(3.0);
        assertThat(counter(AfterCommitBlobPurger.REJECTED_KEYS_METRIC)).isZero();
    }

    @Test
    @DisplayName("dispatchTask runs the whole body on a 'blob-purge-' worker, never the calling thread")
    void should_runTaskOnBlobPurgeThread_when_dispatchTaskWithProductionExecutor() throws Exception {
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) new AsyncConfig().blobPurgeExecutor();
        AtomicReference<String> taskThread = new AtomicReference<>();
        CountDownLatch ran = new CountDownLatch(1);
        AfterCommitBlobPurger purger = new AfterCommitBlobPurger(r2, executor, meters);

        try {
            purger.dispatchTask(() -> {
                taskThread.set(Thread.currentThread().getName());
                ran.countDown();
            }, 2, "test");

            assertThat(ran.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdown();
        }

        assertThat(taskThread.get()).startsWith("blob-purge-").isNotEqualTo(Thread.currentThread().getName());
    }

    @Test
    @DisplayName("dispatchTask rejection is WARN-logged with the key count only, counted, and never throws")
    void should_logCountAndCount_when_dispatchTaskRejected() {
        TaskExecutor rejecting = task -> {
            throw new TaskRejectedException("full");
        };
        AfterCommitBlobPurger purger = new AfterCommitBlobPurger(r2, rejecting, meters);

        assertThatCode(() -> purger.dispatchTask(() -> { }, 4, "salon-purge")).doesNotThrowAnyException();

        assertThat(counter(AfterCommitBlobPurger.REJECTED_KEYS_METRIC)).isEqualTo(4.0);
        assertThat(logs.list).filteredOn(e -> e.getLevel() == Level.WARN).singleElement()
                .satisfies(e -> assertThat(e.getFormattedMessage()).contains("keyCount=4").contains("salon-purge"));
    }

    @Test
    @DisplayName("item 7: with no transaction synchronization the purge is SKIPPED with a WARN (count only)")
    void should_skipAndWarn_when_noTransactionSynchronization() {
        List<Runnable> submitted = new ArrayList<>();
        AfterCommitBlobPurger purger = new AfterCommitBlobPurger(r2, submitted::add, meters);

        purger.purgeAfterCommit(List.of("avatars/secret-id/a.jpg", "avatars/secret-id/b.jpg"), "avatar-purge");

        assertThat(submitted).as("no commit is observable, so nothing may be dispatched").isEmpty();
        verify(r2, never()).deleteFiles(anyCollection());
        assertThat(logs.list).filteredOn(e -> e.getLevel() == Level.WARN).singleElement()
                .satisfies(e -> assertThat(e.getFormattedMessage())
                        .contains("keyCount=2").contains("avatar-purge").doesNotContain("secret-id"));
    }

    @Test
    @DisplayName("PERF-2: every submitted task carries its key count, so a truncated shutdown can report orphaned keys")
    void should_submitKeyCountedTask_when_dispatching() {
        List<Runnable> submitted = new ArrayList<>();
        AfterCommitBlobPurger purger = new AfterCommitBlobPurger(r2, submitted::add, meters);

        purger.dispatch(List.of("avatars/u/a.jpg", "avatars/u/b.jpg", "avatars/u/c.jpg"), "test");
        purger.dispatchTask(() -> { }, 7, "salon-purge");

        assertThat(submitted).hasSize(2).allSatisfy(t -> assertThat(t).isInstanceOf(AsyncConfig.BlobPurgeTask.class));
        assertThat(submitted).extracting(t -> ((AsyncConfig.BlobPurgeTask) t).keyCount()).containsExactly(3, 7);
    }

    private double counter(String name) {
        return meters.get(name).counter().count();
    }

    private static void fireAfterCommit() {
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCommit();
        }
    }
}
