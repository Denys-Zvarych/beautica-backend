package com.beautica.support;

import org.awaitility.core.ConditionTimeoutException;
import org.awaitility.core.TerminalFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;

import static org.awaitility.Awaitility.await;

/**
 * Proves a racing request is genuinely BLOCKED on a Postgres lock, instead of inferring it from "it has
 * not finished after N seconds".
 *
 * <p>A fixed {@code latch.await(2, SECONDS)} in a lock race IT is a timing window twice over: too short
 * and a slow-but-unblocked request still looks blocked; too long and the waiter's own transaction-local
 * 3s {@code lock_timeout} fires and turns the race outcome into a 409. Polling {@code pg_stat_activity}
 * removes both: the holder is released the moment the waiter shows {@code wait_event_type = 'Lock'} with
 * a live blocker, well inside {@code lock_timeout}, and a waiter that completes without ever blocking
 * (the pre-fix, unlocked read) fails fast.
 */
public final class LockWaiters {

    private static final Duration POLL_INTERVAL = Duration.ofMillis(20);

    private LockWaiters() {
    }

    /**
     * Polls until some other backend in this database is waiting on a heavyweight lock held by another
     * backend while running a statement containing {@code queryFragment} (case-insensitive).
     *
     * @param waiterDone counted down when the racing request returns; reaching zero first fails fast
     * @param atMost     upper bound for the waiter to REACH its lock query (not for the lock wait itself:
     *                   once it waits, it is observed within one poll interval)
     * @return {@code true} once the waiter is observed blocked; {@code false} if it finished without
     * blocking or never blocked within {@code atMost}
     */
    public static boolean awaitBlockedOnLock(JdbcTemplate jdbcTemplate, String queryFragment,
                                             CountDownLatch waiterDone, Duration atMost) {
        try {
            await().pollDelay(Duration.ZERO)
                    .pollInterval(POLL_INTERVAL)
                    .atMost(atMost)
                    .failFast("the racing request finished without ever waiting on a lock",
                            () -> waiterDone.getCount() == 0)
                    .until(() -> Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                            "SELECT count(*) > 0 FROM pg_stat_activity"
                                    + " WHERE datname = current_database()"
                                    + " AND pid <> pg_backend_pid()"
                                    + " AND wait_event_type = 'Lock'"
                                    + " AND cardinality(pg_blocking_pids(pid)) > 0"
                                    + " AND query ILIKE ?",
                            Boolean.class, "%" + queryFragment + "%")));
            return true;
        } catch (ConditionTimeoutException | TerminalFailureException notBlocked) {
            return false;
        }
    }
}
