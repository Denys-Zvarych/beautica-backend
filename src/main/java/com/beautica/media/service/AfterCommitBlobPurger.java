package com.beautica.media.service;

import com.beautica.config.AsyncConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Hands already-dereferenced R2 keys to the bounded {@code blobPurgeExecutor} strictly after the current
 * transaction commits (backend-perf P-M2). An {@code afterCommit} callback still holds its pooled JDBC
 * connection, so the R2 {@code DeleteObjects} round-trip must not run on it: the callback only submits.
 *
 * <p><b>Guarantees.</b> A rolled-back transaction purges nothing (the synchronization never fires).
 * {@link #purgeAfterCommit} MUST be called inside a transaction: with no active synchronization it SKIPS the
 * purge with a WARN (same rule as {@code AccountBlobPurgeRegistrar} and {@code SalonService}) — deleting
 * blobs for a write whose commit cannot be observed risks destroying objects a live row still points at.
 * Never throws: a rejected submit or a failed delete is an accepted orphan blob, logged at WARN with a key
 * COUNT only (keys embed user/entity UUIDs).
 *
 * <p><b>Observability (perf P-L4).</b> Every orphaned key is counted: {@value #REJECTED_KEYS_METRIC} by the
 * number of keys a rejected submit dropped, {@value #FAILED_KEYS_METRIC} by the number of keys R2 could not
 * delete (the whole batch when the delete call itself throws). Alert on a non-zero rate. Purges still QUEUED
 * when {@code blobPurgeExecutor}'s shutdown grace expires are reported by the pool itself
 * ({@code AsyncConfig.BlobPurgeLossReportingTaskExecutor}: WARN {@code event=blob_purges_lost_on_shutdown}
 * with task and key counts) — a counter incremented by a terminating JVM is never scraped, so the log line
 * is the record. Every task is submitted as an {@code AsyncConfig.BlobPurgeTask} carrying its key count for
 * exactly that report.
 *
 * <p>Callers verify key ownership (own-prefix) BEFORE handing keys here; this class deletes what it is given.
 */
@Slf4j
@Component
public class AfterCommitBlobPurger {

    static final String REJECTED_KEYS_METRIC = "beautica.blob_purge.rejected.keys";
    static final String FAILED_KEYS_METRIC = "beautica.blob_purge.failed.keys";

    private final R2StorageService r2;
    private final TaskExecutor executor;
    private final Counter rejectedKeys;
    private final Counter failedKeys;

    public AfterCommitBlobPurger(R2StorageService r2, @Qualifier("blobPurgeExecutor") TaskExecutor executor,
                                 MeterRegistry meterRegistry) {
        this.r2 = r2;
        this.executor = executor;
        this.rejectedKeys = Counter.builder(REJECTED_KEYS_METRIC)
                .description("R2 keys orphaned because blobPurgeExecutor rejected the purge task")
                .baseUnit("keys")
                .register(meterRegistry);
        this.failedKeys = Counter.builder(FAILED_KEYS_METRIC)
                .description("R2 keys orphaned because the R2 delete failed")
                .baseUnit("keys")
                .register(meterRegistry);
    }

    /**
     * Registers ONE after-commit purge of {@code keys} (copied now). No-op for an empty collection. Must be
     * called inside a transaction: with no active synchronization the purge is SKIPPED with a WARN (key count
     * only) — defensive, every production caller runs inside {@code @Transactional} / {@code txWrite}.
     */
    public void purgeAfterCommit(Collection<String> keys, String context) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            log.warn("Blob purge skipped: no active transaction synchronization (context={}, keyCount={}, "
                    + "keys=[omitted])", context, keys.size());
            return;
        }
        List<String> snapshot = List.copyOf(keys);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                dispatch(snapshot, context);
            }
        });
    }

    /**
     * Submits the purge to {@code blobPurgeExecutor} now. For callers already running after commit (e.g.
     * inside their own {@code afterCommit}); everyone else uses {@link #purgeAfterCommit}.
     */
    public void dispatch(Collection<String> keys, String context) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        List<String> snapshot = List.copyOf(keys);
        dispatchTask(() -> purgeNow(snapshot, context), snapshot.size(), context);
    }

    /**
     * Submits an arbitrary after-commit purge body to {@code blobPurgeExecutor} as ONE task — for purges that
     * must keep their own multi-step order (e.g. the salon purge: R2 batch first, THEN its REQUIRES_NEW DB
     * steps) yet must not run on the committing thread. Call from an {@code afterCommit} callback (or with no
     * transaction). The task owns its own failure handling; a rejected submit is WARN-logged with
     * {@code keyCount} only and counted on {@value #REJECTED_KEYS_METRIC}. Never throws.
     *
     * @param task     the purge body; must not throw (it runs detached)
     * @param keyCount number of blobs the task would delete — for the WARN and the rejected-keys counter
     * @param context  short non-PII label for the log line
     */
    public void dispatchTask(Runnable task, int keyCount, String context) {
        try {
            executor.execute(new AsyncConfig.BlobPurgeTask(task, keyCount));
        } catch (TaskRejectedException ex) {
            rejectedKeys.increment(keyCount);
            log.warn("Blob purge rejected by blobPurgeExecutor; blobs orphaned (context={}, keyCount={}, keys=[omitted])",
                    context, keyCount);
        }
    }

    private void purgeNow(List<String> keys, String context) {
        try {
            Set<String> failed = r2.deleteFiles(keys);
            if (failed != null && !failed.isEmpty()) {
                failedKeys.increment(failed.size());
                log.warn("R2 blob purge partially failed (context={}, failedCount={}, keys=[omitted])",
                        context, failed.size());
            }
        } catch (RuntimeException ex) {
            failedKeys.increment(keys.size());
            log.warn("R2 blob purge failed (context={}, keyCount={}, keys=[omitted]): {}",
                    context, keys.size(), ex.getClass().getSimpleName());
        }
    }
}
