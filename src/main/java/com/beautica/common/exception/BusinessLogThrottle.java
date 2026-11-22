package com.beautica.common.exception;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Phase 500 audit (perf LOW) — caps the business-4xx log line per {@code METHOD path-template}: at most
 * {@link #LINES_PER_WINDOW} lines per {@link #WINDOW}, so a 409 storm (guest slot-taken) cannot write one line per
 * request through the synchronous console appender. The first request after a window that suppressed lines
 * carries that window's suppressed count, which the handler logs as ONE summary line.
 *
 * <p>Lock-free: one {@link AtomicReference} swap per window roll, one {@link AtomicInteger} increment per request.
 * Counts are approximate under contention (a straggler that read the old window just before the swap is counted
 * there, after its summary was taken) — acceptable for a log cap. Memory is bounded: at most
 * {@link #MAX_TRACKED_KEYS} keys (path templates are a finite set anyway; {@code [unknown]} is one key); an evicted
 * key simply restarts with a fresh window.
 *
 * <p>Not a Spring bean: {@link GlobalExceptionHandler} owns one instance, so {@code @WebMvcTest} slices that
 * {@code @Import} the handler need no extra bean.
 */
final class BusinessLogThrottle {

    static final int LINES_PER_WINDOW = 20;
    static final Duration WINDOW = Duration.ofMinutes(1);
    static final int MAX_TRACKED_KEYS = 512;

    private static final long WINDOW_MILLIS = WINDOW.toMillis();

    /** Outcome for one request: log the line or not, plus the previous window's suppressed count (0 = none). */
    record Decision(boolean logLine, int suppressedInPreviousWindow) {
    }

    private record Window(long startMillis, AtomicInteger count) {

        static Window startingAt(long startMillis) {
            return new Window(startMillis, new AtomicInteger());
        }

        int suppressed() {
            return Math.max(0, count.get() - LINES_PER_WINDOW);
        }
    }

    private final Clock clock;
    private final Cache<String, AtomicReference<Window>> windows;

    BusinessLogThrottle(Clock clock) {
        this.clock = clock;
        this.windows = Caffeine.newBuilder()
                .maximumSize(MAX_TRACKED_KEYS)
                .executor(Runnable::run)
                .build();
    }

    Decision acquire(String method, String template) {
        long now = clock.millis();
        AtomicReference<Window> slot = windows.get(method + ' ' + template, key -> new AtomicReference<>(Window.startingAt(now)));
        Window current = slot.get();
        int suppressedInPrevious = 0;
        if (now - current.startMillis() >= WINDOW_MILLIS) {
            Window next = Window.startingAt(now);
            if (slot.compareAndSet(current, next)) {
                suppressedInPrevious = current.suppressed();
            }
            current = slot.get();
        }
        boolean logLine = current.count().incrementAndGet() <= LINES_PER_WINDOW;
        return new Decision(logLine, suppressedInPrevious);
    }

    /** Number of keys currently tracked — for the bounded-memory test. */
    long trackedKeys() {
        windows.cleanUp();
        return windows.estimatedSize();
    }
}
