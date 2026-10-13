package com.beautica.auth.phoneotp;

import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * Resource-less transaction manager for unit tests: real begin/commit/rollback bookkeeping via
 * {@link AbstractPlatformTransactionManager} (so {@code TransactionSynchronizationManager
 * .isActualTransactionActive()} is truthful) without a database.
 */
final class RecordingTransactionManager extends AbstractPlatformTransactionManager {

    final List<String> events = new ArrayList<>();

    @Override
    protected Object doGetTransaction() {
        return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        events.add("begin");
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
        events.add("commit");
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
        events.add("rollback");
    }
}
