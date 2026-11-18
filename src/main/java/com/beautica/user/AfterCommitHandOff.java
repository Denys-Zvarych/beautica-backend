package com.beautica.user;

import org.slf4j.Logger;
import org.springframework.core.task.TaskExecutor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.RejectedExecutionException;

/**
 * B516 — hands a mail task to an executor AFTER the caller's transaction commits (immediately when none is active, the
 * unit-test path). A saturated executor ({@code AbortPolicy}) is logged and dropped: by then the write is committed,
 * so a {@link RejectedExecutionException} escaping {@code afterCommit} would turn a successful request into a 500
 * (the {@code PasswordResetSmsChannel#handOff} rule). Only the exception class is logged — never an address.
 */
public final class AfterCommitHandOff {

    private AfterCommitHandOff() {
    }

    public static void submit(TaskExecutor executor, Runnable task, Logger log, String label) {
        Runnable handOff = () -> {
            try {
                executor.execute(task);
            } catch (RejectedExecutionException rejected) {
                log.warn("{} hand-off refused: {}", label, rejected.getClass().getSimpleName());
            }
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            handOff.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                handOff.run();
            }
        });
    }
}
