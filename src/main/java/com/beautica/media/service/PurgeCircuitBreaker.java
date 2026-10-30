package com.beautica.media.service;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 417 audit-fix cycle 2 (backend-perf) — a minimal circuit breaker for {@link CloudflareCachePurger}.
 *
 * <p>During a Cloudflare outage one 100-URL chunk can hold a {@code blobPurgeExecutor} thread ~15 s (connect +
 * read, the retry pause, then again), and that pool has only 2 threads. After {@value #FAILURE_THRESHOLD}
 * consecutive chunks end in a transport / 5xx / 429 failure the breaker opens for {@link #OPEN_DURATION}; while
 * open the purger sends nothing. When the window expires the next chunk is a trial: a failure re-opens it at once
 * (the failure count is still at the threshold), a success closes it and resets the count. A trial is sent
 * without the in-chunk retry (see {@link #isTrial()}), so it holds a pool thread for at most one attempt.
 *
 * <p>Lock-free, safe for the 2-thread pool: two threads may both send a trial after expiry — accepted.
 */
final class PurgeCircuitBreaker {

    static final int FAILURE_THRESHOLD = 3;
    static final Duration OPEN_DURATION = Duration.ofSeconds(60);

    private static final long CLOSED = 0L;

    private final Clock clock;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong openUntilMillis = new AtomicLong(CLOSED);
    private final AtomicLong warnedWindow = new AtomicLong(CLOSED);

    PurgeCircuitBreaker(Clock clock) {
        this.clock = clock;
    }

    /** {@code true} while an open window is running — the caller must skip the request. */
    boolean isOpen() {
        long until = openUntilMillis.get();
        return until != CLOSED && clock.millis() < until;
    }

    /**
     * {@code true} when the circuit is closed but the failure count is still at/over the threshold — i.e. the next
     * chunk is the trial after an open window expired. Call only after {@link #isOpen()} returned {@code false}.
     */
    boolean isTrial() {
        return consecutiveFailures.get() >= FAILURE_THRESHOLD && !isOpen();
    }

    /** {@code true} exactly once per open window, so the caller logs at most one WARN per window. */
    boolean claimWarningForCurrentWindow() {
        long until = openUntilMillis.get();
        long previous = warnedWindow.get();
        return previous != until && warnedWindow.compareAndSet(previous, until);
    }

    void recordSuccess() {
        consecutiveFailures.set(0);
        openUntilMillis.set(CLOSED);
    }

    /** One chunk ended in a transport / 5xx / 429 failure (after its retry). */
    void recordOutageFailure() {
        if (consecutiveFailures.incrementAndGet() >= FAILURE_THRESHOLD) {
            openUntilMillis.set(clock.millis() + OPEN_DURATION.toMillis());
        }
    }
}
