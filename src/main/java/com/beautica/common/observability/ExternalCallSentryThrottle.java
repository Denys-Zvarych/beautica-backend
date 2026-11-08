package com.beautica.common.observability;

import io.sentry.SentryEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 441 audit-fix — shapes what {@link ExternalCallFailure} ERROR lines turn into in Sentry; the log lines
 * themselves are never touched (owner rule: every external failure stays an ERROR line).
 *
 * <ul>
 *   <li><b>Grouping.</b> The event's fingerprint becomes {@code ["external-call", service, reason]}, so every distinct
 *       (service, reason) opens its own Sentry issue (and its own "new issue" e-mail) instead of one issue for the
 *       shared message template.</li>
 *   <li><b>Volume.</b> A budget per (service, reason) with exponential backoff: the first
 *       {@link ExternalCallSentryLimitProperties#maxEventsPerWindow()} events of each window are forwarded, the rest
 *       dropped. A window that dropped anything doubles the next window's length (10m → 20m → 40m …, capped at
 *       {@link ExternalCallSentryLimitProperties#maxWindow()}); a window without drops (or a gap long enough to hold one)
 *       resets it to the base {@link ExternalCallSentryLimitProperties#window()}. A key failing non-stop therefore
 *       settles at ~5 events per day instead of ~720, while the first 5 still pass at once, so a new issue and its
 *       e-mail open within the first minute. FCM per-device fan-out, Turnstile/SMS visitor-driven rejections and a
 *       revoked provider token can no longer drain the 5k events/month quota.</li>
 *   <li><b>Suppressed count.</b> Events dropped for a key are counted; the next event forwarded for that key carries
 *       the tag {@value #TAG_SUPPRESSED}{@code =<n>} and the count restarts at zero.</li>
 * </ul>
 *
 * <p>The key comes from the Sentry tags {@link ExternalCallFailure#TAG_SERVICE} / {@link ExternalCallFailure#TAG_REASON}
 * (MDC set around the log call, promoted to tags by {@code SentryOptionsConfig}'s context tags) — never from the
 * message text. An event without both tags is returned untouched. The {@code op} and {@code http} arguments of the
 * log line are deliberately NOT in the fingerprint or the key: the reason already names the failure class, and adding
 * them would split one outage into several issues (more "new issue" e-mails, more budgets, more quota) — they stay
 * readable in the event message.
 *
 * <p>Memory is bounded: at most {@link ExternalCallSentryLimitProperties#maxKeys()} keys, least recently used evicted
 * (an evicted key simply starts afresh at the base window, losing its backoff and suppressed count). Thread-safe: the
 * map and every per-key state are guarded by this instance's monitor; it is hit only for ERROR events, so contention
 * is negligible.
 */
@Component
@ConditionalOnProperty(name = "sentry.dsn")
public class ExternalCallSentryThrottle {

    static final String FINGERPRINT_PREFIX = "external-call";
    static final String TAG_SUPPRESSED = "ext_call_suppressed";
    private static final char KEY_SEPARATOR = '\u0000';
    private static final long DROP = -1L;

    private final Clock clock;
    private final int maxEventsPerWindow;
    private final long baseWindowMillis;
    private final long maxWindowMillis;
    private final Map<String, KeyState> windows;

    public ExternalCallSentryThrottle(Clock clock, ExternalCallSentryLimitProperties properties) {
        this.clock = clock;
        this.maxEventsPerWindow = properties.maxEventsPerWindow();
        this.baseWindowMillis = properties.window().toMillis();
        this.maxWindowMillis = Math.max(baseWindowMillis, properties.maxWindow().toMillis());
        int maxKeys = properties.maxKeys();
        this.windows = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, KeyState> eldest) {
                return size() > maxKeys;
            }
        };
    }

    /**
     * @return {@code event} unchanged when it is not an external-call event; otherwise {@code event} fingerprinted,
     *         or {@code null} (Sentry's "drop" contract) when its (service, reason) budget is spent
     */
    public SentryEvent apply(SentryEvent event) {
        String service = event.getTag(ExternalCallFailure.TAG_SERVICE);
        String reason = event.getTag(ExternalCallFailure.TAG_REASON);
        if (service == null || reason == null) {
            return event;
        }
        event.setFingerprints(List.of(FINGERPRINT_PREFIX, service, reason));
        long suppressed = tryAcquire(service + KEY_SEPARATOR + reason);
        if (suppressed == DROP) {
            return null;
        }
        if (suppressed > 0) {
            event.setTag(TAG_SUPPRESSED, Long.toString(suppressed));
        }
        return event;
    }

    /** Package-private for the tests: the number of keys currently tracked. */
    synchronized int trackedKeys() {
        return windows.size();
    }

    /** Package-private for the tests: the current window length of a key, or 0 when the key is not tracked. */
    synchronized long windowMillisOf(String service, String reason) {
        KeyState state = windows.get(service + KEY_SEPARATOR + reason);
        return state == null ? 0 : state.length;
    }

    /**
     * @return {@link #DROP} when the key's budget is spent; otherwise the number of events dropped for the key since
     *         its last forwarded event (then reset to zero)
     */
    private synchronized long tryAcquire(String key) {
        long now = clock.millis();
        KeyState state = windows.get(key);
        if (state == null) {
            state = new KeyState(now, baseWindowMillis);
            windows.put(key, state);
        } else if (now - state.start >= state.length) {
            state.roll(now, nextLength(state, now));
        }
        if (state.count >= maxEventsPerWindow) {
            state.dropped = true;
            state.suppressed++;
            return DROP;
        }
        state.count++;
        long suppressed = state.suppressed;
        state.suppressed = 0;
        return suppressed;
    }

    /**
     * Doubles (up to the cap) after a window that dropped events; back to the base after a quiet window — including
     * the case where the gap since the saturated window ended is long enough to have held the doubled window empty.
     */
    private long nextLength(KeyState ended, long now) {
        if (!ended.dropped) {
            return baseWindowMillis;
        }
        long doubled = Math.min(maxWindowMillis, ended.length * 2);
        long idleSinceEnd = now - (ended.start + ended.length);
        return idleSinceEnd >= doubled ? baseWindowMillis : doubled;
    }

    /** Mutable per-key state; only touched under the throttle's monitor. */
    private static final class KeyState {
        private long start;
        private long length;
        private int count;
        private boolean dropped;
        private long suppressed;

        private KeyState(long start, long length) {
            this.start = start;
            this.length = length;
        }

        private void roll(long newStart, long newLength) {
            start = newStart;
            length = newLength;
            count = 0;
            dropped = false;
        }
    }
}
