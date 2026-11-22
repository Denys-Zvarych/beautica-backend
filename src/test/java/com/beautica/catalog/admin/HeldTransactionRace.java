package com.beautica.catalog.admin;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Deterministic row-lock race for the catalog-admin ITs (B502 edit vs decision, B503 decision vs decision): runs
 * {@code first} in a transaction held open after it finishes, starts {@code second} on another thread, waits until
 * {@code second} is done or a backend is waiting on a lock ({@code pg_stat_activity}), then commits {@code first}.
 * Returns {@code second}'s result or throws its failure. No sleeps: latches plus a bounded poll.
 */
final class HeldTransactionRace {

    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final long POLL_MILLIS = 50;

    private final PlatformTransactionManager transactionManager;
    private final JdbcTemplate jdbcTemplate;

    HeldTransactionRace(PlatformTransactionManager transactionManager, JdbcTemplate jdbcTemplate) {
        this.transactionManager = transactionManager;
        this.jdbcTemplate = jdbcTemplate;
    }

    <T> T run(Runnable first, Supplier<T> second) throws Exception {
        // Own 2-thread pool, shut down after use: never the shared common pool, and no thread outlives the test.
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        try {
            CountDownLatch firstDone = new CountDownLatch(1);
            CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> holdOpen(first, firstDone, releaseFirst),
                    executor);
            if (!firstDone.await(WAIT.toSeconds(), TimeUnit.SECONDS)) {
                holder.get(POLL_MILLIS, TimeUnit.MILLISECONDS);
                throw new AssertionError("first never ran");
            }
            CompletableFuture<T> contender = CompletableFuture.supplyAsync(second, executor);
            awaitBlockedOrDone(contender);
            releaseFirst.countDown();
            holder.get(WAIT.toSeconds(), TimeUnit.SECONDS);
            return result(contender);
        } finally {
            // A failed wait above must still let the holder commit/roll back, so its row lock never leaks into the
            // next test.
            releaseFirst.countDown();
            executor.shutdown();
            if (!executor.awaitTermination(WAIT.toSeconds(), TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        }
    }

    private void holdOpen(Runnable first, CountDownLatch firstDone, CountDownLatch releaseFirst) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            first.run();
            firstDone.countDown();
            try {
                if (!releaseFirst.await(WAIT.toSeconds(), TimeUnit.SECONDS)) {
                    throw new IllegalStateException("holder never released");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
        });
    }

    private static <T> T result(CompletableFuture<T> contender) throws Exception {
        try {
            return contender.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw ex;
        }
    }

    private void awaitBlockedOrDone(CompletableFuture<?> contender) throws Exception {
        Instant deadline = Instant.now().plus(WAIT);
        while (Instant.now().isBefore(deadline)) {
            try {
                contender.get(POLL_MILLIS, TimeUnit.MILLISECONDS);
                return;
            } catch (ExecutionException done) {
                return;
            } catch (TimeoutException stillRunning) {
                Integer waiting = jdbcTemplate.queryForObject("SELECT count(*) FROM pg_stat_activity "
                        + "WHERE wait_event_type = 'Lock' AND datname = current_database()", Integer.class);
                if (waiting != null && waiting > 0) {
                    return;
                }
            }
        }
        throw new AssertionError("contender neither finished nor blocked on a lock");
    }
}
